package com.abc.daodian.card.render

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.TypedValue
import android.widget.RemoteViews
import com.abc.daodian.R
import com.abc.daodian.card.CardActionReceiver
import com.abc.daodian.card.CardHub
import com.abc.daodian.card.CardReader
import com.abc.daodian.card.spec.CardSpec
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.card.spec.Ref

/** 桌面上一块的实际大小，dp */
data class Space(val width: Float, val height: Float)

/**
 * 照说明画成 RemoteViews：网页填上这一刻的数（[CardPage]），浅色、深色各截一张（[CardShot]），
 * 交给桌面一张图（`setIcon` 带深浅两张，桌面切深色时自己换），能点的地方在图上盖透明的按钮。见 DESIGN.md §8.5
 *
 * 跟着大小变：桌面给的每一种实际大小各截一份，交成「大小 → 画法」的表（Android 12 起），拖大拖小时桌面自己挑；
 * 网页按实际大小排（CSS 里 100% 就是那么大），不用代码估。荣耀桌面只给一种，拖完叫一次重画。
 */
object CardViews {

    /** 一张卡片最多盖几块能点的 */
    private const val MAX_TAPS = 24

    /** 桌面给的大小太多时只截前两种（竖屏、横屏）：每种两张图，再多桌面那边的图片内存放不下 */
    private const val MAX_SPACES = 2

    /** 画出来的结果：每种大小一份，和画的时候碰到的问题（脚本报错、裁边、没画出来） */
    class Built(val views: Map<Space, RemoteViews>, val problems: List<String>, val text: String = "")

    /** 系统此刻是不是深色（桌面跟系统走，不跟 app 自己的深色设置） */
    fun nightNow(): Boolean =
        (android.content.res.Resources.getSystem().configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * 照 [spaces] 里每一种大小各画一份（同一次取的数）。[cardId] = 桌面上那一块是哪张卡片，点了发给它；预览是 null，点不了。
     * [both] = 浅色、深色都截；false 只截系统此刻那一种，两个槽填同一张 —— 点了勾先这样交，快；另一种随后再补（`CardRenderer`）。
     * 绑的那条没了（提醒删了）：整张卡画成一行灰字，长按移除（§8.5）。返回的每一份都还没挂「点空白处」，由调的一方挂
     */
    suspend fun build(context: Context, spec: CardSpec, cardId: Long?, spaces: List<Space>, both: Boolean = true): Built {
        val reader = CardReader(context, CardHub.sources)
        val actions = CardSpecs.taps(spec.html).mapNotNull(Ref::parse)
        reader.gone(CardSpecs.refs(spec.html) + actions)?.let { what -> return Built(spaces.associateWith { gone(context, what) }, emptyList()) }
        val problems = mutableListOf<String>()
        var text = ""
        val night = nightNow()
        suspend fun shot(space: Space, dark: Boolean) =
            CardShot.shoot(context, CardPage.build(spec.html, space.width, space.height, if (dark) CardPage.DARK else CardPage.LIGHT) { reader.value(it) }, space.width, space.height)
        val views = spaces.take(MAX_SPACES).associateWith { space ->
            val now = shot(space, night)
            problems += now.problems
            if (text.isEmpty()) text = now.text
            val bitmap = now.bitmap ?: return@associateWith blank(context, spec.name, "这张没画出来，在对话里让它重画")
            val other = if (both) shot(space, !night).bitmap ?: bitmap else bitmap
            val (light, dark) = if (night) other to bitmap else bitmap to other
            RemoteViews(context.packageName, R.layout.card_shot).apply {
                setIcon(R.id.card_image, "setImageIcon", Icon.createWithBitmap(light), Icon.createWithBitmap(dark))
                // 桌面常常是在原来那层上重放（reapply），addView 会接着往后叠：先清空
                removeAllViews(R.id.card_taps)
                if (cardId != null) taps(context, this, cardId, now.taps, reader)
            }
        }
        return Built(views, problems.distinct(), text)
    }

    /**
     * 这一刻画出来会是什么样的指纹：网页、绑上的值、能点的那几块勾没勾、大小、深浅。
     * 和上次交给桌面的一样，就不用再截、再交（`CardRenderer`）：点一个勾，别的卡片不跟着重画
     */
    suspend fun key(context: Context, spec: CardSpec, cardId: Long, spaces: List<Space>): String {
        val reader = CardReader(context, CardHub.sources)
        val refs = CardSpecs.refs(spec.html)
        val taps = CardSpecs.taps(spec.html)
        val what = reader.gone(refs + taps.mapNotNull(Ref::parse))?.let { "gone $it" }
            ?: (refs.map { "$it=${reader.value(it)}" } + taps.map { "$it=${reader.on(it)}" }).joinToString("\n")
        val all = "$cardId\n${spec.size.key}\n${spec.html}\n$what\n${spaces.take(MAX_SPACES)}\n${nightNow()}"
        return java.security.MessageDigest.getInstance("SHA-1").digest(all.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** 几份按大小画的交给桌面：一份就直接给，几份给成「大小 → 画法」的表（Android 12 起），桌面拖到哪挑哪份 */
    fun responsive(views: Map<Space, RemoteViews>): RemoteViews =
        views.values.singleOrNull() ?: RemoteViews(views.mapKeys { (s, _) -> android.util.SizeF(s.width, s.height) })

    /** 勾画在离方框中心多远的左边（勾是 14dp 见方，画在 CheckBox 的最左边） */
    private const val MARK_HALF = 7f

    /**
     * 图上盖按钮：网页里 `data-tap` 那几块在哪，就盖在哪。只认模块交的动作（点了还会再查一次）。
     * 盖的是真的 CheckBox（Android 12 起小组件的有状态按钮）：点下去桌面自己当场画上勾（或画回空框），
     * 不等存库、重截图；同时把动作交给 app，存完重截一张，新图里已经是勾上的，CheckBox 也复位。
     * 勾画在 CheckBox 的最左边，所以把它的左边挪到方框那儿（方框左边那一小段就点不到了）
     */
    private suspend fun taps(context: Context, views: RemoteViews, cardId: Long, taps: List<CardShot.Tap>, reader: CardReader) {
        val vocab = CardHub.vocabulary
        taps.filter { t -> Ref.parse(t.action)?.let { vocab.action(it) } != null }.take(MAX_TAPS).forEachIndexed { n, t ->
            val on = reader.on(t.action)
            val left = (t.boxX - MARK_HALF).coerceIn(t.x, t.x + t.w - 2 * MARK_HALF)
            views.addView(R.id.card_taps, RemoteViews(context.packageName, if (on) R.layout.card_check_from_on else R.layout.card_check_from_off).apply {
                setViewLayoutMargin(R.id.card_tap, RemoteViews.MARGIN_LEFT, left, TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutMargin(R.id.card_tap, RemoteViews.MARGIN_TOP, t.y, TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutWidth(R.id.card_tap, t.x + t.w - left, TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutHeight(R.id.card_tap, t.h, TypedValue.COMPLEX_UNIT_DIP)
                // 每次画都从「和图一致」开始：勾着的那样是 checked
                setCompoundButtonChecked(R.id.card_tap, on)
                // 每块一条不同的 data，PendingIntent 才不会互相盖掉
                setOnCheckedChangeResponse(
                    R.id.card_tap,
                    RemoteViews.RemoteResponse.fromPendingIntent(
                        PendingIntent.getBroadcast(
                            context, 0,
                            Intent(context, CardActionReceiver::class.java)
                                .setAction(CardActionReceiver.ACTION)
                                .setData(Uri.parse("daodian-card://$cardId/$n"))
                                .putExtra(CardActionReceiver.EXTRA_DO, t.action),
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                )
            })
        }
    }

    /**
     * 外壳。桌面刷新时不一定重新铺一遍布局，常常是把这份 RemoteViews 在原来那层上重放一遍（reapply），
     * addView 就接着往后叠 —— 所以先清空，每次都从空的画起
     */
    private fun root(context: Context) = RemoteViews(context.packageName, R.layout.card_root).apply {
        removeAllViews(android.R.id.background)
    }

    /** 绑的那条没了：「绑的那条提醒没了」一行灰字，底下一行「长按移除」 */
    private fun gone(context: Context, what: String): RemoteViews {
        val root = root(context)
        root.addView(android.R.id.background, RemoteViews(context.packageName, R.layout.card_text_body).apply {
            setTextViewText(R.id.card_text, "绑的那条${what}没了")
            setColor(R.id.card_text, "setTextColor", R.color.widget_muted)
        })
        root.addView(android.R.id.background, RemoteViews(context.packageName, R.layout.card_text_caption).apply { setTextViewText(R.id.card_text, "长按移除") })
        return root
    }

    /** 桌面上还没钉东西、钉的那张不在了、没画出来：两行字 */
    fun blank(context: Context, title: String, hint: String): RemoteViews {
        val root = root(context)
        root.addView(android.R.id.background, RemoteViews(context.packageName, R.layout.card_text_title).apply { setTextViewText(R.id.card_text, title) })
        root.addView(android.R.id.background, RemoteViews(context.packageName, R.layout.card_text_caption).apply { setTextViewText(R.id.card_text, hint) })
        return root
    }
}

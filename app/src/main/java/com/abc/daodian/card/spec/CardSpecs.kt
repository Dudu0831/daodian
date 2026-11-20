package com.abc.daodian.card.spec

import com.abc.daodian.card.plugin.CardField
import com.abc.daodian.card.plugin.CardSource
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 卡片说明（name、size、一页网页）↔ 库里存的 JSON，和那道闸门（§8.5）。
 *
 * 网页里绑数据只有两种写法，闸门照这两种扫出全部绑定：
 * - 字里的 `{{reminder.days_left #7}}`：截图前换成那个值的字；
 * - 脚本里的 `D["reminder.items #7"]`：一组行、一组数这种要自己排的，拿到的是 JSON（见 `render/CardPage`）。
 * 能点的地方写 `data-tap="reminder.done #7"`；清单、列表每一行的值里自带 `tap`，脚本里照抄到 data-tap 上。
 *
 * 闸门查：绑的名字有没有、参数个数对不对、至少绑一条你的记录（只绑 time 的不收）、写死的 data-tap 是不是模块交的动作、
 * 不往外面拉东西（断网，拉也拉不到，早点告诉它）。数是不是抄进了字里拦不全，写在给模型的说明里。
 * 拦下的原因写成模型能照着改的话，由它自己改了再交。画出来裁没裁边、是不是空的，截一次图才知道（`Cards.trial`）。
 */
object CardSpecs {

    /** 只跟日历有关的那一类：不算「你的记录」 */
    const val TIME = "time"

    /** 网页最长多少字：一张卡片用不了这么多，再长多半是在网页里抄数据 */
    const val MAX_HTML = 16_000

    sealed interface Result {
        data class Ok(val spec: CardSpec) : Result
        data class Bad(val problems: List<String>) : Result
    }

    /** 能绑的、能点的：各模块交的 + 卡片模块自带的时间 */
    class Vocabulary(val sources: List<CardSource>) {

        fun field(ref: Ref): CardField? = sources.firstOrNull { it.id == ref.source }?.fields?.firstOrNull { it.name == ref.name }

        fun action(ref: Ref): CardField? = sources.firstOrNull { it.id == ref.source }?.actions?.firstOrNull { it.name == ref.name }

        fun knows(source: String) = sources.any { it.id == source }
    }

    private val mapper = ObjectMapper()

    /** 存进库的那一份 */
    fun write(spec: CardSpec): String = mapper.writeValueAsString(linkedMapOf("name" to spec.name, "size" to spec.size.key, "html" to spec.html))

    /** 库里存的（已经查过的）。读不出是 null —— 11-19 以前零件拼的那版也读不出，卡片上写「让它重画」 */
    fun read(json: String): CardSpec? {
        val o = runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isObject } ?: return null
        val html = o.path("html").asText("").takeIf { it.isNotBlank() } ?: return null
        val size = CardSize.of(o.path("size").asText(null)) ?: return null
        return CardSpec(o.path("name").asText("").trim(), size, html)
    }

    // 花括号全转义：安卓的正则（ICU）不认没转义的 }
    private val DATA = Regex("""\bD\s*\[\s*(["'])([^"']+)\1\s*\]""")
    private val TAP = Regex("""data-tap\s*=\s*(["'])([^"']*)\1""")
    private val OUTSIDE = Regex("""(<script[^>]+\bsrc\s*=|<link\b|<iframe\b|@import|\b(?:src|href)\s*=\s*["']?\s*(?:https?:)?//|url\(\s*["']?\s*(?:https?:)?//|\bfetch\s*\(|XMLHttpRequest)""", RegexOption.IGNORE_CASE)

    /** 网页里绑了哪些（字里的 `{{…}}` + 脚本里的 `D["…"]`），按出现的先后、不重复。写不成名字的不算 */
    fun refs(html: String): List<Ref> =
        (Ref.holes(html) + DATA.findAll(html).map { it.groupValues[2] }).mapNotNull(Ref::parse).distinctBy { it.toString() }

    /** 写死在网页里的 `data-tap`（脚本照行里的 tap 填的那些查不到，点的时候模块自己认） */
    fun taps(html: String): List<String> = TAP.findAll(html).map { it.groupValues[2].trim() }.filter { it.isNotEmpty() && !it.contains("{{") }.toList()

    /** 查一份模型交的 */
    fun check(name: String?, size: String?, html: String?, v: Vocabulary): Result {
        val problems = mutableListOf<String>()
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) problems += "name 要写（几个字：「去旧金山」）"
        val s = CardSize.of(size)
        if (s == null) problems += "size 只能是 ${CardSize.entries.joinToString("、") { it.key }}"
        val page = html?.trim().orEmpty()
        when {
            page.isEmpty() -> problems += "html 要写：这张卡片的网页"
            page.length > MAX_HTML -> problems += "网页太长了（${page.length} 字，最多 $MAX_HTML）。卡片就一小块，数由代码填，不用在网页里抄数据"
        }
        if (page.isNotEmpty()) verify(page, v, problems)
        return if (problems.isEmpty()) Result.Ok(CardSpec(n.take(12), s!!, page)) else Result.Bad(problems.distinct().take(8))
    }

    private fun verify(html: String, v: Vocabulary, problems: MutableList<String>) {
        if (Regex("""<\s*(html|head|body)\b""", RegexOption.IGNORE_CASE).containsMatchIn(html))
            problems += "不要写 <html> <head> <body>，只写卡片里面那一段（外壳、尺寸、颜色变量代码会套上）"
        OUTSIDE.find(html)?.let { problems += "卡片是断网截图的，外面的东西（「${it.value.trim()}」）拉不进来：图、字体、脚本都写在网页里，图用内嵌 svg" }

        Ref.holes(html).filter { Ref.parse(it) == null }.forEach { problems += "{{$it}} 写不成名字：要写成 {{reminder.days_left #7}} 这样" }
        val refs = refs(html)
        refs.forEach { ref ->
            if (!v.knows(ref.source)) { problems += "没有 ${ref.source} 这一类（${ref}），能绑的见说明"; return@forEach }
            val f = v.field(ref) ?: run { problems += "${ref.source} 没有 ${ref.name}（${ref}）"; return@forEach }
            if (ref.args.size !in f.minArgs..f.maxArgs) problems += "${ref.source}.${f.name} 的参数是「${f.args.ifEmpty { "不要参数" }}」，你写的是 $ref"
        }
        val taps = taps(html).map { it to Ref.parse(it) }
        taps.forEach { (raw, a) ->
            when {
                a == null -> problems += "data-tap=\"$raw\" 写不成动作：要写成 reminder.done #7 这样"
                !v.knows(a.source) -> problems += "data-tap=\"$raw\"：没有 ${a.source} 这一类"
                v.action(a) == null -> problems += "data-tap=\"$raw\"：${a.source} 没有 ${a.name} 这个动作"
                a.args.size !in v.action(a)!!.minArgs..v.action(a)!!.maxArgs ->
                    problems += "data-tap=\"$raw\"：${a.source}.${a.name} 的参数是「${v.action(a)!!.args.ifEmpty { "不要参数" }}」"
            }
        }
        // 至少绑一条你的记录：提醒或记账里的都算，点「完成」的地方也算
        val sources = (refs.map { it.source } + taps.mapNotNull { it.second?.source }).filter { v.knows(it) }
        if (sources.none { it != TIME }) {
            val records = v.sources.filter { it.id != TIME }.joinToString("或") { it.label }
            problems += "卡片至少要绑一条${records}里的记录（只绑 time 的不收）。要数的事还没记下，先建一条再画"
        }
    }
}

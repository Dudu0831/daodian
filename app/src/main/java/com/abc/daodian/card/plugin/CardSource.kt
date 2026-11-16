package com.abc.daodian.card.plugin

import android.content.Context

/**
 * 模块给桌面卡片的那一份：能绑的数据、能点的动作。见 DESIGN.md §8.5
 *
 * 卡片长什么样由模型拼（零件 + 绑定，`card/spec/CardSpec`），数从哪来由各模块交：卡片模块只认「`reminder.days_left #7`
 * 这个名字归 reminder 管」，问它要值、叫它办事，不 import 它。模块在接头上交出去（`Module.cards`）；
 * 要多给一样数据，在 [fields] 里多列一个名字就行，卡片模块不用改。
 */
interface CardSource {

    /** 「reminder」，和模块 id 一样：绑定写成 `reminder.days_left #7`，前缀就是它。存在卡片说明里，不改 */
    val id: String

    /** 「提醒」 */
    val label: String

    /** 给模型的一句：参数里的编号怎么查到（「#7 是提醒的 # 编号，用 list_reminders 查」）。拼进卡片工具包的说明 */
    val brief: String

    /** 能绑的。名字不带前缀（`days_left`） */
    val fields: List<CardField>

    /** 能点的（卡片上的按钮、列表行尾的圈）。名字不带前缀（`done`） */
    val actions: List<CardField> get() = emptyList()

    /**
     * [name] 此刻的值。卡片每次重画都问（跨零点、数据变了、桌面来要），别在里面做慢事。
     * 没有这个名字、参数不对、那件事没了都是 null —— 卡片上那一块就空着
     */
    suspend fun read(context: Context, name: String, args: List<String>): CardValue?

    /** 桌面上点了 [name] 这个动作。办成了 true。办完要不要让卡片重画，模块自己喊 [CardBoard.changed] */
    suspend fun act(context: Context, name: String, args: List<String>): Boolean = false
}

/**
 * 一个能绑的名字（或能点的动作）。[args] 是写给模型看的参数样子：「#提醒编号」「today|week|month [类别]」，
 * 方括号里的可以不写 —— 校验按它数参数个数
 */
data class CardField(val name: String, val kind: CardKind, val what: String, val args: String = "") {

    private val tokens: List<String> get() = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    val minArgs: Int get() = tokens.count { !it.startsWith("[") }

    val maxArgs: Int get() = tokens.size
}

/** 值的样子。零件挑着认：大数字要 [NUMBER]，列表要 [ROWS]，柱子要 [SERIES]…… */
enum class CardKind(val label: String) {
    TEXT("字"), NUMBER("数"), FLAG("是 / 否"), ROWS("一组行"), SERIES("一组数"), ACTION("动作")
}

sealed interface CardValue {
    data class Text(val text: String) : CardValue
    data class Number(val value: Long) : CardValue
    data class Flag(val on: Boolean) : CardValue

    /** 列表的几行。总共有 [total] 行（只给了前几行时用来写「还有 N 条」） */
    data class Rows(val rows: List<CardRow>, val total: Int = rows.size) : CardValue

    /** 柱子：一组数、每根底下的字、哪一根是「现在」（墨色；它后面的还没到，只画底线）。没有「现在」是 -1 */
    data class Series(val values: List<Long>, val labels: List<String> = emptyList(), val current: Int = -1) : CardValue

    /** 嵌进字里时写成什么 */
    fun asText(): String = when (this) {
        is Text -> text
        is Number -> value.toString()
        is Flag -> if (on) "是" else "否"
        is Rows -> rows.joinToString("、") { it.title }
        is Series -> values.sum().toString()
    }
}

/**
 * 列表的一行：左边一句、右边一句。[accent] 右边那句用朱砂（「当天」）；[done] 办完了，整行变灰。
 * [action] 是行尾那个圈点下去做什么（`reminder.done #12`），没有就不画圈
 */
data class CardRow(
    val title: String,
    val note: String = "",
    val accent: Boolean = false,
    val done: Boolean = false,
    val action: String? = null
)

/**
 * 模块喊一声「我的数据变了」，桌面上的卡片重画。刷新是推的（桌面在别的进程，收不到 Room 的流，§8.2）：
 * 漏喊的后果是卡片上是旧数，到跨零点或桌面 30 分钟来要一次时才对。
 */
object CardBoard {

    @Volatile
    private var redraw: suspend (Context) -> Unit = {}

    /** 卡片模块启动时装进来（`CardModule.onAppStart`）：插口只认这一个函数，不碰卡片模块里面 */
    fun install(redraw: suspend (Context) -> Unit) {
        this.redraw = redraw
    }

    suspend fun changed(context: Context) = redraw(context)
}

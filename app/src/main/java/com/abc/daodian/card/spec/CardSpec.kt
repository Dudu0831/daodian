package com.abc.daodian.card.spec

import com.abc.daodian.card.own.OwnDef

/**
 * 一张卡片的说明：模型拼的，代码照着画（`render/CardViews`）。见 DESIGN.md §8.5
 *
 * 只说「用哪些零件、怎么排、每个数绑到哪」，不存数：数每次重画时按绑定现取（`CardReader`）。
 * JSON 的写法见 [CardSpecs]，工具包的说明里有给模型看的那一份（`assistant/CardGuide`）。
 */
data class CardSpec(
    /** 卡片叫什么（「喝水」）：对话里、痕上认它用，不画在卡片上 */
    val name: String,
    val size: CardSize,
    /** 卡片自己记的数：喝了几杯、买菜清单…… 键 → 怎么记 */
    val own: Map<String, OwnDef>,
    /** 最外面那一层，竖排或横排 */
    val body: Block
)

/**
 * 桌面上的四种大小，一种一个桌面组件（`CardWidget` 的子类）。[key] 存进库、写在说明里，不改。
 * [widthDp]×[heightDp] 是对话里预览的大小，和这台荣耀桌面上量出来的差不多
 */
enum class CardSize(val key: String, val label: String, val widthDp: Int, val heightDp: Int) {
    S2X2("2x2", "2×2", 164, 164),
    S4X1("4x1", "4×1", 352, 72),
    S4X2("4x2", "4×2", 352, 164),
    S4X4("4x4", "4×4", 352, 352);

    companion object {
        fun of(key: String?): CardSize? = entries.firstOrNull { it.key == key?.trim()?.lowercase()?.replace('×', 'x') }
    }
}

/** 颜色只有三种，深浅色由桌面按 `@color` 解析（§8.2 第 3 条） */
enum class Tone { INK, MUTED, ACCENT }

/** 字的颜色：平时 [base]；[accentWhen] 是真的时候朱砂，[mutedWhen] 是真的时候灰（朱砂优先） */
data class Paint(val base: Tone = Tone.INK, val accentWhen: Ref? = null, val mutedWhen: Ref? = null)

enum class TextStyle { TITLE, BODY, CAPTION }

enum class Align { START, CENTER, END }

/** 大数字的四档字号（sp） */
enum class NumberSize(val sp: Float) { S(30f), M(44f), L(56f), XL(72f) }

/** 按钮长什么样：一颗字（「＋1」）、一枚墨印麦克风、一个圈 */
enum class ButtonIcon { TEXT, MIC, CHECK }

/** 零件。[grow] = 撑满它那一排（横排里横着撑、竖排里竖着撑）剩下的地方 */
sealed interface Block {
    val grow: Boolean

    data class Column(val children: List<Block>, override val grow: Boolean = false) : Block
    data class Row(val children: List<Block>, override val grow: Boolean = false) : Block

    /** 一行字。[text] 里能嵌绑定：「还差 {{own.cups.left}} 杯」 */
    data class Text(
        val text: String,
        val style: TextStyle = TextStyle.BODY,
        val align: Align = Align.START,
        val paint: Paint = Paint(),
        override val grow: Boolean = false
    ) : Block

    /** 大数字，前后各一个小字。[value] 是一个绑定或者一个字面数 */
    data class Number(
        val value: String,
        val before: String = "",
        val after: String = "",
        val size: NumberSize? = null,
        val paint: Paint = Paint(),
        override val grow: Boolean = false
    ) : Block

    /** 墨条：[value] 是 0 到 100；两头各一句小字 */
    data class Bar(val value: String, val left: String = "", val right: String = "", override val grow: Boolean = false) : Block

    /** 几行：左一句、右一句，行尾可能有个圈。最多 [max] 行，多的写「还有 N 条」 */
    data class ListRows(val rows: Ref, val max: Int = 3, override val grow: Boolean = false) : Block

    /** 柱子：一组数，最高的顶满 [height] dp */
    data class Bars(val values: Ref, val labels: Boolean = true, val height: Int = 44, override val grow: Boolean = false) : Block

    /** 卡片自己的清单（own 里的 [key]），点一下勾掉 */
    data class Checklist(val key: String, val max: Int = 5, override val grow: Boolean = false) : Block

    /** [of] 个里亮了几个 */
    data class Dots(val value: String, val of: Int, override val grow: Boolean = false) : Block

    /** 走秒（own 里的 [key]），系统自己一秒一秒往下数 */
    data class Timer(val key: String, override val grow: Boolean = false) : Block

    /** 点下去做 [action]（`own.add cups 1`、`reminder.done #7`） */
    data class Button(val label: String, val icon: ButtonIcon, val action: Ref, override val grow: Boolean = false) : Block

    /** 一根细线 */
    data class Rule(override val grow: Boolean = false) : Block
}

/** 这个零件和它里面的绑了哪些名字（只算取值的，按钮的动作不算） */
fun Block.bindings(): List<Ref> = when (this) {
    is Block.Column -> children.flatMap { it.bindings() }
    is Block.Row -> children.flatMap { it.bindings() }
    is Block.Text -> Ref.holes(text).mapNotNull(Ref::parse) + listOfNotNull(paint.accentWhen, paint.mutedWhen)
    is Block.Number -> Ref.holes(value + before + after).mapNotNull(Ref::parse) + listOfNotNull(paint.accentWhen, paint.mutedWhen)
    is Block.Bar -> Ref.holes(value + left + right).mapNotNull(Ref::parse)
    is Block.ListRows -> listOf(rows)
    is Block.Bars -> listOf(values)
    is Block.Dots -> Ref.holes(value).mapNotNull(Ref::parse)
    is Block.Button -> Ref.holes(label).mapNotNull(Ref::parse)
    is Block.Checklist, is Block.Timer, is Block.Rule -> emptyList()
}

/** 数一共几个零件（排法也算）。说明太大的拦下 */
fun Block.count(): Int = 1 + when (this) {
    is Block.Column -> children.sumOf { it.count() }
    is Block.Row -> children.sumOf { it.count() }
    else -> 0
}

/** 最深套了几层 */
fun Block.depth(): Int = 1 + when (this) {
    is Block.Column -> children.maxOfOrNull { it.depth() } ?: 0
    is Block.Row -> children.maxOfOrNull { it.depth() } ?: 0
    else -> 0
}

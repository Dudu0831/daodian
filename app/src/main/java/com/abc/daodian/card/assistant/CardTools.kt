package com.abc.daodian.card.assistant

import com.abc.daodian.card.CardFace
import com.abc.daodian.card.CardTemplate
import com.abc.daodian.card.data.Card
import com.abc.daodian.card.plugin.CardSubject
import com.abc.daodian.runtime.kit.Kit
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate

/**
 * 卡片的工具背后要的。真机上是 `Cards` + `CardHub`（[CardPlugin] 接的），测试里是假的 —— 工具不要 Context。
 */
interface CardDesk {

    /** 能钉哪几种东西：各模块交的（`plugin/CardSource` 的 id、名字、给助手的那段） */
    val sources: List<Source>

    /** [source] 里 [ref] 那件事此刻的样子。没有这种来源、没有这件事都是 null */
    suspend fun subject(source: String, ref: Long): CardSubject?

    suspend fun cards(): List<Card>

    /** 备一张并请桌面放上去。[Placing.asked] = 桌面弹了「添加」的框 */
    suspend fun pin(source: String, ref: Long, template: CardTemplate, start: LocalDate?): Placing

    /** 已有的一张换成画另一件事。没有这张是 null */
    suspend fun retarget(cardId: Long, source: String, ref: Long, start: LocalDate?): Card?

    data class Source(val id: String, val label: String, val brief: String)

    data class Placing(val card: Card, val asked: Boolean)
}

/**
 * 桌面卡片的三个工具，收在工具包 `card` 里交给助手（DESIGN.md §6.11、§8.5）：钉一张、看有哪些、换一张画的事。
 * 卡片上的数不归模型：它只说「钉哪件事、用哪种模板」，剩几天由代码每天自己算。
 *
 * 这里不认识提醒、账：能钉什么、编号怎么查，是各来源自己写的那一段（`CardSource.brief`），拼进说明里。
 */
object CardTools {

    fun kit(desk: CardDesk): Kit =
        Kit(KIT, SUMMARY, guide(desk.sources), listOf(PinCardTool(desk), ListCardsTool(desk), ChangeCardTool(desk)))

    const val KIT = "card"

    const val PIN = "pin_card"
    const val LIST = "list_cards"
    const val CHANGE = "change_card"

    val NAMES = setOf(PIN, LIST, CHANGE)

    const val SUMMARY = "桌面卡片。他想把某件事钉到手机桌面上看还剩几天（「把牛奶过期放到桌面」「春节倒数放桌面」" +
        "「桌面那张换成护照」），或者问桌面上钉了什么时开"

    fun guide(sources: List<CardDesk.Source>): String = """
桌面卡片：把一件事钉在手机桌面上，卡片自己每天数还剩几天，不用你再管。
- 一张卡片钉一件事，用 source（哪一种）和 ref（那一种里的编号）指它。能钉的：
${sources.joinToString("\n") { "  · ${it.id}（${it.label}）：${it.brief}" }}
- 模板两种：big 是 2×2 的大数字（默认用它）；bar 是 4×1 的一根墨条，从起点走到那天，他说「进度」「墨条」「一条」或者给了起点才用。
  start 只有 bar 用：他说了从哪天算起（「租约去年 12 月 30 日开始」）就填，没说填 null。
- pin_card 之后桌面会弹一个框，他点「添加」卡片才出现。照结果里的话告诉他下一步，一句就够；不要说「已经放好了」。
- 「桌面那张换成 X」：list_cards 看是哪张，change_card 换。模板换不了（那是桌面上那一块的大小），要换模板就再钉一张、旧的他自己长按移除。
- 卡片你拿不掉：他说不要了，告诉他长按那张卡片移除。
- 不要替他钉：他没说放桌面，就不钉。
""".trimIndent()

    internal val mapper = ObjectMapper()

    internal fun parse(arguments: String): JsonNode? = runCatching { mapper.readTree(arguments) }.getOrNull()?.takeIf { it.isObject }

    internal fun JsonNode.long(field: String): Long? = get(field)?.takeIf { it.canConvertToLong() }?.asLong()

    internal fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    internal fun JsonNode.day(field: String): LocalDate? = text(field)?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    internal fun obj(vararg props: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(*props),
        "required" to props.map { it.first },
        "additionalProperties" to false
    )

    internal fun source(desk: CardDesk) = mapOf(
        "type" to "string", "enum" to desk.sources.map { it.id },
        "description" to "钉的是哪一种：" + desk.sources.joinToString("、") { "${it.id} ${it.label}" }
    )

    internal val REF = mapOf("type" to "integer", "description" to "那一种里的编号（提醒就是 # 后面的数字：#12 填 12）")

    internal val START = mapOf(
        "type" to listOf("string", "null"),
        "description" to "墨条从哪天算起，YYYY-MM-DD。他说了才填；没说、或者是大数字，填 null（从今天算）"
    )

    /** 「c3 · 大数字 · 提醒 #12 牛奶过期（2027-02-13，还剩 92 天） · 在桌面上」 */
    internal suspend fun line(desk: CardDesk, card: Card, today: LocalDate): String {
        val template = CardTemplate.of(card.template)?.label ?: card.template
        val label = desk.sources.firstOrNull { it.id == card.source }?.label ?: card.source
        val what = desk.subject(card.source, card.ref)
            ?.let { "$label #${card.ref} ${it.title}（${it.day}，${CardFace.of(it, card, today).left}）" } ?: "钉的那件事已经不在了"
        return "c${card.id} · $template · $what · " + if (card.widgetId != null) "在桌面上" else "备好了，还没放上桌面"
    }

    /** 查不到那件事时回给模型的话 */
    internal fun missing(desk: CardDesk, source: String, ref: Long): String =
        if (desk.sources.none { it.id == source }) "source 只能是 ${desk.sources.joinToString("、") { it.id }}。"
        else "${desk.sources.first { it.id == source }.label}里没有 #$ref，先查一下编号。"
}

internal class PinCardTool(private val desk: CardDesk) : Tool {
    override val name = CardTools.PIN
    override val effect = ToolEffect.WRITE
    override val description = "把一件事钉成一张桌面卡片：卡片自己每天数还剩几天。桌面会弹框，他点「添加」才出现。"
    override val parameters = CardTools.obj(
        "source" to CardTools.source(desk),
        "ref" to CardTools.REF,
        "template" to mapOf("type" to "string", "enum" to listOf("big", "bar"), "description" to "big 大数字（2×2，默认）/ bar 墨条（4×1）"),
        "start" to CardTools.START
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome = with(CardTools) {
        val o = parse(arguments) ?: return ToolOutcome("没钉。参数不是合法的 JSON。", ok = false)
        val source = o.text("source").orEmpty()
        val ref = o.long("ref") ?: return ToolOutcome("没钉。ref 要填编号。", ok = false)
        val template = CardTemplate.of(o.text("template")) ?: return ToolOutcome("没钉。template 只能是 big 或 bar。", ok = false)
        val subject = desk.subject(source, ref) ?: return ToolOutcome("没钉。${missing(desk, source, ref)}", ok = false)
        val placing = desk.pin(source, ref, template, o.day("start"))
        val next = if (placing.asked) "桌面弹了一个框，他点「添加」卡片就出现在桌面上。"
        else "这个桌面不能直接加：请他长按桌面 → 小组件 → 到点「${if (template == CardTemplate.BIG) "数日子" else "墨条"}」，加上就是这张。"
        ToolOutcome(
            "备好了：${template.label} · ${subject.title}\n${line(desk, placing.card, context.now.toLocalDate())}\n$next",
            ok = true, ref = ref
        )
    }
}

internal class ListCardsTool(private val desk: CardDesk) : Tool {
    override val name = CardTools.LIST
    override val effect = ToolEffect.READ
    override val description = "桌面上钉着哪些卡片：编号、模板、钉的是哪件事、还剩几天、放没放上桌面。"
    override val parameters = CardTools.obj()

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val cards = desk.cards()
        if (cards.isEmpty()) return ToolOutcome("桌面上还没有钉卡片。", ok = true)
        val today = context.now.toLocalDate()
        return ToolOutcome(cards.map { CardTools.line(desk, it, today) }.joinToString("\n"), ok = true)
    }
}

internal class ChangeCardTool(private val desk: CardDesk) : Tool {
    override val name = CardTools.CHANGE
    override val effect = ToolEffect.WRITE
    override val description = "已有的一张卡片换成钉另一件事（模板不变）。卡片编号用 list_cards 查。"
    override val parameters = CardTools.obj(
        "card_id" to mapOf("type" to "integer", "description" to "卡片 c 编号的数字：c3 填 3"),
        "source" to CardTools.source(desk),
        "ref" to CardTools.REF,
        "start" to CardTools.START
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome = with(CardTools) {
        val o = parse(arguments) ?: return ToolOutcome("没换。参数不是合法的 JSON。", ok = false)
        val cardId = o.long("card_id") ?: return ToolOutcome("没换。card_id 要填卡片 c 编号的数字。", ok = false)
        val source = o.text("source").orEmpty()
        val ref = o.long("ref") ?: return ToolOutcome("没换。ref 要填编号。", ok = false)
        val subject = desk.subject(source, ref) ?: return ToolOutcome("没换。${missing(desk, source, ref)}", ok = false)
        val card = desk.retarget(cardId, source, ref, o.day("start"))
            ?: return ToolOutcome("没换。没有 c$cardId 这张卡片，先用 list_cards 查。", ok = false)
        ToolOutcome("换好了：${subject.title}\n${line(desk, card, context.now.toLocalDate())}", ok = true, ref = ref)
    }
}

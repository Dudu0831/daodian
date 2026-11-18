package com.abc.daodian.card.assistant

import com.abc.daodian.card.Cards
import com.abc.daodian.card.data.Card
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpec
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.runtime.kit.Kit
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 卡片的工具背后要的。真机上是 `Cards` + `CardHub`（[CardPlugin] 接的），测试里是假的 —— 工具不要 Context。
 */
interface CardDesk {

    /** 能绑什么、能点什么（各模块交的 + 时间） */
    val vocabulary: CardSpecs.Vocabulary

    suspend fun cards(): List<Card>

    suspend fun create(raw: String, spec: CardSpec): Card

    /** 换掉说明。没有这张是 null */
    suspend fun redraw(id: Long, raw: String, spec: CardSpec): Card?

    /** 请桌面放上去。没有这张是 null */
    suspend fun pin(id: Long): Cards.Pinned?
}

/**
 * 桌面卡片的三个工具，收在工具包 `card` 里交给助手（DESIGN.md §6.11、§8.5）：画（新画或改）、钉到桌面、看有哪些。
 * 卡片上的数不归模型：它写的是绑定，数由代码每次重画时现取。卡片上的东西要改，就去改那条提醒或那笔账。
 */
object CardTools {

    fun kit(desk: CardDesk): Kit = Kit(
        KIT, SUMMARY, CardGuide.guide(desk.vocabulary.sources),
        listOf(DesignCardTool(desk), PinCardTool(desk), ListCardsTool(desk))
    )

    const val KIT = "card"

    const val DESIGN = "design_card"
    const val PIN = "pin_card"
    const val LIST = "list_cards"

    val NAMES = setOf(DESIGN, PIN, LIST)

    const val SUMMARY = "桌面卡片。他想在手机桌面上放一张卡片看点什么（某件事还剩几天、行李清单、这周花了多少、今天几件事），" +
        "或者要改桌面上那张、问桌面上有什么时开"

    internal val mapper = ObjectMapper()

    internal fun parse(arguments: String): JsonNode? = runCatching { mapper.readTree(arguments) }.getOrNull()?.takeIf { it.isObject }

    internal fun JsonNode.long(field: String): Long? = get(field)?.takeIf { it.canConvertToLong() }?.asLong()

    internal fun obj(vararg props: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(*props),
        "required" to props.map { it.first },
        "additionalProperties" to false
    )

    internal val CARD_ID = mapOf("type" to "integer", "description" to "卡片 c 编号的数字：c3 填 3")

    /** 「c3「去旧金山」2×2 · 在桌面上」 */
    internal fun head(card: Card): String =
        "c${card.id}「${card.name}」${CardSize.of(card.size)?.label ?: card.size} · " + if (card.widgetId != null) "在桌面上" else "还没放上桌面"
}

internal class DesignCardTool(private val desk: CardDesk) : Tool {
    override val name = CardTools.DESIGN
    override val effect = ToolEffect.WRITE
    override val description = "画一张桌面卡片，或者改已有的一张（整份说明重写）。对话里会出预览；放上桌面另调 pin_card。说明怎么写见工具包说明。"
    override val parameters = CardTools.obj(
        "card_id" to mapOf("type" to listOf("integer", "null"), "description" to "改哪张（c 编号的数字）；新画一张填 null"),
        "spec" to mapOf("type" to "string", "description" to "卡片说明，一整段 JSON 文本")
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome = with(CardTools) {
        val o = parse(arguments) ?: return ToolOutcome("没画。参数不是合法的 JSON。", ok = false)
        val raw = o.get("spec")?.asText()?.trim().orEmpty()
        val spec = when (val r = CardSpecs.check(raw, desk.vocabulary)) {
            is CardSpecs.Result.Bad -> return ToolOutcome(
                "没画。说明有问题：\n" + r.problems.joinToString("\n") { "- $it" } + "\n这是说明写错了，改好再调一次，不用问用户。", ok = false
            )
            is CardSpecs.Result.Ok -> r.spec
        }
        val id = o.long("card_id")
        val old = id?.let { i -> desk.cards().firstOrNull { it.id == i } ?: return ToolOutcome("没画。没有 c$i 这张卡片，先用 list_cards 查。", ok = false) }
        // 在桌面上的那张换了大小：桌面上那一块的大小改不了，另画一张
        if (old != null && old.widgetId != null && old.size != spec.size.key) {
            val card = desk.create(raw, spec)
            return ToolOutcome(
                "画好了：c${card.id}「${card.name}」（${spec.size.label}）。\n大小从 ${CardSize.of(old.size)?.label} 改成 ${spec.size.label}：桌面上那一块的大小改不了，所以另画了这张 c${card.id}；" +
                    "c${old.id} 还在桌面上没动。对话里已经出了预览；他要放上去就 pin_card c${card.id}，旧的让他长按移除。",
                ok = true, ref = card.id
            )
        }
        val card = if (old != null) desk.redraw(old.id, raw, spec)!! else desk.create(raw, spec)
        ToolOutcome(
            "画好了：c${card.id}「${card.name}」（${spec.size.label}）。\n对话里已经出了预览，不用再描述它。" +
                if (card.widgetId != null) "桌面上那张已经跟着变了。" else "他要放到桌面就 pin_card。",
            ok = true, ref = card.id
        )
    }
}

internal class PinCardTool(private val desk: CardDesk) : Tool {
    override val name = CardTools.PIN
    override val effect = ToolEffect.WRITE
    override val description = "把画好的一张卡片放到桌面：桌面会弹框，他点「添加」才出现。"
    override val parameters = CardTools.obj("card_id" to CardTools.CARD_ID)

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome = with(CardTools) {
        val id = parse(arguments)?.long("card_id") ?: return ToolOutcome("没钉。card_id 要填卡片 c 编号的数字。", ok = false)
        val p = desk.pin(id) ?: return ToolOutcome("没钉。没有 c$id 这张卡片，先用 list_cards 查。", ok = false)
        val size = CardSize.of(p.card.size)?.label ?: p.card.size
        when {
            p.already -> ToolOutcome("没钉。c$id「${p.card.name}」已经在桌面上了。", ok = false)
            p.asked -> ToolOutcome("备好了：c$id「${p.card.name}」\n桌面弹了一个框，他点「添加」卡片就出现在桌面上。", ok = true, ref = id)
            else -> ToolOutcome(
                "备好了：c$id「${p.card.name}」\n这个桌面不能直接加：请他长按桌面 → 小组件 → 到点「卡片 $size」，加上就是这张。", ok = true, ref = id
            )
        }
    }
}

internal class ListCardsTool(private val desk: CardDesk) : Tool {
    override val name = CardTools.LIST
    override val effect = ToolEffect.READ
    override val outputGoesStale = true
    override val description = "画过哪些卡片：编号、名字、大小、在不在桌面上，和每张现在的说明（改的时候照它改）。"
    override val parameters = CardTools.obj()

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val cards = desk.cards()
        if (cards.isEmpty()) return ToolOutcome("还没画过卡片。", ok = true)
        return ToolOutcome(cards.joinToString("\n\n") { c -> CardTools.head(c) + "\n说明：${c.spec}" }, ok = true)
    }
}

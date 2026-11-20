package com.abc.daodian.card.assistant

import com.abc.daodian.card.data.Card
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpec
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.runtime.kit.Kit
import com.abc.daodian.runtime.model.Effort
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

    /** 存之前照标准尺寸截一张看：画出来有什么问题、这一刻绑上的是什么值 */
    suspend fun trial(spec: CardSpec): CardTrial

    suspend fun create(spec: CardSpec): Card

    /** 换掉说明。没有这张是 null */
    suspend fun redraw(id: Long, spec: CardSpec): Card?
}

/**
 * [problems] 不空就不存，退回给模型改；[filled] 是「reminder.days_left #1 = 35」这样的几行；
 * [text] 是截出来那张卡上实际看得到的字 —— 模型看不见图，跟他描述时照这个说，别照它打算画的说
 */
class CardTrial(val problems: List<String>, val filled: List<String>, val text: String = "")

/**
 * 桌面卡片的两个工具，收在工具包 `card` 里交给助手（DESIGN.md §6.11、§8.5）：画（新画或改）、看有哪些。
 * 放上桌面不给模型：桌面会弹框，由他自己点预览底下的「放到桌面 ›」。
 * 卡片上的数不归模型：它写的是绑定，数由代码每次重画时现取。卡片上的东西要改，就去改那条提醒或那笔账。
 */
object CardTools {

    fun kit(desk: CardDesk): Kit = Kit(
        KIT, SUMMARY, CardGuide.guide(desk.vocabulary.sources),
        listOf(DesignCardTool(desk), ListCardsTool(desk)),
        // 画得好不好看差在想没想：开着这个包的几轮至少多想，选了深想照深想（§8.5）
        effort = Effort.HIGH
    )

    const val KIT = "card"

    const val DESIGN = "design_card"
    const val LIST = "list_cards"

    val NAMES = setOf(DESIGN, LIST)

    const val SUMMARY = "桌面卡片（你写一页网页，截成图放到桌面上）。他想在手机桌面上放一张卡片看点什么（某件事还剩几天、行李清单、这周花了多少、今天几件事），" +
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
    override val description = "画一张桌面卡片，或者改已有的一张（整页重写）。卡片是你写的一小页网页，怎么写见工具包说明。" +
        "交上来先截一张看，有问题会退回给你改；画好了对话里会出预览，他自己点预览底下的「放到桌面」放上去。"
    override val parameters = CardTools.obj(
        "card_id" to mapOf("type" to listOf("integer", "null"), "description" to "改哪张（c 编号的数字）；新画一张填 null"),
        "name" to mapOf("type" to "string", "description" to "卡片叫什么，几个字（「去旧金山」），不画在卡片上"),
        "size" to mapOf("type" to "string", "enum" to CardSize.entries.map { it.key }, "description" to "多大"),
        "html" to mapOf("type" to "string", "description" to "卡片的网页：只写里面那一段（div、style、script），数用 {{名字}} 或 D[\"名字\"] 绑")
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome = with(CardTools) {
        val o = parse(arguments) ?: return ToolOutcome("没画。参数不是合法的 JSON。", ok = false)
        val spec = when (val r = CardSpecs.check(o.get("name")?.asText(), o.get("size")?.asText(), o.get("html")?.asText(), desk.vocabulary)) {
            is CardSpecs.Result.Bad -> return ToolOutcome(
                "没画。网页有问题：\n" + r.problems.joinToString("\n") { "- $it" } + "\n这是网页写错了，改好再调一次，不用问用户。", ok = false
            )
            is CardSpecs.Result.Ok -> r.spec
        }
        val id = o.long("card_id")
        val old = id?.let { i -> desk.cards().firstOrNull { it.id == i } ?: return ToolOutcome("没画。没有 c$i 这张卡片，先用 list_cards 查。", ok = false) }
        val trial = desk.trial(spec)
        if (trial.problems.isNotEmpty()) return ToolOutcome(
            "没画。照 ${spec.size.label}（${spec.size.widthDp}×${spec.size.heightDp}px）截了一张，有问题：\n" +
                trial.problems.joinToString("\n") { "- $it" } + filled(trial) + "\n改好再调一次，不用问用户。",
            ok = false
        )
        // 在桌面上的那张换了大小：桌面上那一块的大小改不了，另画一张
        if (old != null && old.widgetId != null && old.size != spec.size.key) {
            val card = desk.create(spec)
            return ToolOutcome(
                "画好了：c${card.id}「${card.name}」（${spec.size.label}）。\n大小从 ${CardSize.of(old.size)?.label} 改成 ${spec.size.label}：桌面上那一块的大小改不了，所以另画了这张 c${card.id}；" +
                    "c${old.id} 还在桌面上没动。对话里已经出了预览；他要放上去就点预览底下的「放到桌面」，旧的让他长按移除。" + filled(trial),
                ok = true, ref = card.id
            )
        }
        val card = if (old != null) desk.redraw(old.id, spec)!! else desk.create(spec)
        ToolOutcome(
            "画好了：c${card.id}「${card.name}」（${spec.size.label}）。\n对话里已经出了预览，不用再描述它。" +
                (if (card.widgetId != null) "桌面上那张已经跟着变了。" else "他要放到桌面就点预览底下的「放到桌面」。") + filled(trial),
            ok = true, ref = card.id
        )
    }

    /** 这一刻绑上的值，让模型对一眼 */
    private fun filled(t: CardTrial): String =
        (if (t.filled.isEmpty()) "" else "\n这一刻绑上的值：\n" + t.filled.joinToString("\n") { "- $it" }) +
            (if (t.text.isEmpty()) "" else "\n截出来卡上看得到的字：${t.text}\n跟他说画了什么时照这个说，没画出来的别说成画了。")
}

internal class ListCardsTool(private val desk: CardDesk) : Tool {
    override val name = CardTools.LIST
    override val effect = ToolEffect.READ
    override val outputGoesStale = true
    override val description = "画过哪些卡片：编号、名字、大小、在不在桌面上，和每张现在的网页（改的时候照它改）。"
    override val parameters = CardTools.obj()

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val cards = desk.cards()
        if (cards.isEmpty()) return ToolOutcome("还没画过卡片。", ok = true)
        return ToolOutcome(cards.joinToString("\n\n") { c ->
            CardTools.head(c) + "\n" + (CardSpecs.read(c.spec)?.html?.let { "网页：\n$it" } ?: "旧的画法（零件拼的），要改就照现在的写法整页重写")
        }, ok = true)
    }
}

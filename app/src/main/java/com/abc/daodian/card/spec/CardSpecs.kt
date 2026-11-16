package com.abc.daodian.card.spec

import com.abc.daodian.card.own.OwnData
import com.abc.daodian.card.own.OwnDef
import com.abc.daodian.card.plugin.CardField
import com.abc.daodian.card.plugin.CardKind
import com.abc.daodian.card.plugin.CardSource
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate

/**
 * 卡片说明的 JSON ↔ [CardSpec]，和那道闸门：零件认不认识、绑的名字有没有、绑上的值合不合这个零件、按钮的动作有没有。
 * 拦下的原因写成模型能照着改的话（「第 3 块 number 绑的 reminder.title 是字，不是数」），由它自己改了再交（§8.5）。
 *
 * 写法（工具包说明里给模型的那一份在 `assistant/CardGuide`，两边一起改）：
 * ```
 * {"name":"喝水","size":"2x2","own":{"cups":{"count":0,"max":8,"reset":"daily"}},
 *  "body":{"column":[{"text":"喝水","style":"title"},{"number":"{{own.cups}}","after":"/ 8 杯"},
 *                    {"button":"＋1","do":"own.add cups 1"}]}}
 * ```
 */
object CardSpecs {

    /** 一张卡片最多几个零件（排法也算） */
    const val MAX_BLOCKS = 24

    /** 最多套几层 */
    const val MAX_DEPTH = 5

    sealed interface Result {
        data class Ok(val spec: CardSpec) : Result
        data class Bad(val problems: List<String>) : Result
    }

    /** 能绑的、能点的：各模块交的 + 卡片自己的（own） */
    class Vocabulary(val sources: List<CardSource>) {

        fun field(ref: Ref, own: Map<String, OwnDef>): CardField? =
            if (ref.source == OWN) OwnData.fields(own).firstOrNull { it.name == ref.name }
            else sources.firstOrNull { it.id == ref.source }?.fields?.firstOrNull { it.name == ref.name }

        fun action(ref: Ref): CardField? =
            if (ref.source == OWN) OwnData.ACTIONS.firstOrNull { it.name == ref.name }
            else sources.firstOrNull { it.id == ref.source }?.actions?.firstOrNull { it.name == ref.name }

        fun knows(source: String) = source == OWN || sources.any { it.id == source }
    }

    const val OWN = "own"

    private val mapper = ObjectMapper()

    /** 读 + 过闸门 */
    fun check(json: String, vocabulary: Vocabulary): Result {
        val problems = mutableListOf<String>()
        val spec = parse(json, problems) ?: return Result.Bad(problems.ifEmpty { listOf("说明不是合法的 JSON") })
        if (problems.isEmpty()) verify(spec, vocabulary, problems)
        return if (problems.isEmpty()) Result.Ok(spec) else Result.Bad(problems.distinct().take(6))
    }

    /** 只读不查（库里存的、已经查过的）。读不出是 null */
    fun read(json: String): CardSpec? = parse(json, mutableListOf())

    // ---------------- 读

    private fun parse(json: String, problems: MutableList<String>): CardSpec? {
        val root = runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isObject } ?: return null
        val name = root.text("name") ?: run { problems += "name 要写（几个字：「喝水」）"; "" }
        val size = CardSize.of(root.text("size")) ?: run {
            problems += "size 只能是 ${CardSize.entries.joinToString("、") { it.key }}"; CardSize.S2X2
        }
        val own = linkedMapOf<String, OwnDef>()
        root.path("own").fields().forEach { (key, node) ->
            if (!OwnData.validKey(key)) problems += "own 的键「$key」只用小写字母、数字、下划线，字母开头"
            ownDef(node)?.let { own[key] = it } ?: run { problems += "own.$key 认不出：写成 count、list、text、date、timer 里的一种" }
        }
        val bodyNode = root.path("body")
        if (bodyNode.isMissingNode) { problems += "body 要写（最外面是 column 或 row）"; return null }
        val counter = intArrayOf(0)
        val body = block(bodyNode, problems, counter) ?: return null
        if (body !is Block.Column && body !is Block.Row) problems += "body 最外面要是 column 或 row"
        return CardSpec(name.trim().take(12), size, own, body)
    }

    private fun ownDef(n: JsonNode): OwnDef? {
        val daily = n.text("reset") == "daily"
        return when {
            n.has("count") -> OwnDef.Count(n.path("count").asLong(0), n.path("max").takeIf { it.isNumber }?.asLong(), daily)
            n.has("list") -> OwnDef.Checklist(n.path("list").mapNotNull { it.asText()?.trim()?.takeIf(String::isNotEmpty) }.take(OwnData.MAX_ITEMS), daily)
            n.has("text") -> OwnDef.Note(n.path("text").asText(""))
            n.has("date") -> runCatching { LocalDate.parse(n.path("date").asText().take(10)) }.getOrNull()?.let { OwnDef.Day(it) }
            n.has("timer") -> OwnDef.Timer(n.path("timer").asLong(180).coerceIn(1, OwnData.MAX_TIMER_SECONDS))
            else -> null
        }
    }

    private val KINDS = listOf("column", "row", "text", "number", "bar", "list", "bars", "checklist", "dots", "timer", "button", "rule")

    private fun block(n: JsonNode, problems: MutableList<String>, counter: IntArray): Block? {
        val at = ++counter[0]
        val kind = KINDS.firstOrNull { n.has(it) } ?: run {
            problems += "第 $at 块认不出：要有 ${KINDS.joinToString("、")} 里的一个键"; return null
        }
        val grow = n.path("grow").let { it.asBoolean(false) || it.asInt(0) > 0 }
        fun children(): List<Block> = n.path(kind).takeIf { it.isArray }?.mapNotNull { block(it, problems, counter) }
            ?: run { problems += "第 $at 块 $kind 后面要跟一组零件 [ ]"; emptyList() }
        fun paint() = Paint(
            base = when (n.text("color")) { "muted" -> Tone.MUTED; "accent" -> Tone.ACCENT; else -> Tone.INK },
            accentWhen = n.text("accent_when")?.let { flagRef(it, at, problems) },
            mutedWhen = n.text("muted_when")?.let { flagRef(it, at, problems) }
        )
        return when (kind) {
            "column" -> Block.Column(children(), grow)
            "row" -> Block.Row(children(), grow)
            "text" -> Block.Text(
                n.path("text").asText(""),
                when (n.text("style")) { "title" -> TextStyle.TITLE; "caption" -> TextStyle.CAPTION; else -> TextStyle.BODY },
                when (n.text("align")) { "end", "right" -> Align.END; "center" -> Align.CENTER; else -> Align.START },
                paint(), grow
            )
            "number" -> Block.Number(
                n.path("number").asText(""), n.text("before").orEmpty(), n.text("after").orEmpty(),
                n.text("size")?.let { s -> NumberSize.entries.firstOrNull { it.name.equals(s, true) } }, paint(), grow
            )
            "bar" -> Block.Bar(n.path("bar").asText(""), n.text("left").orEmpty(), n.text("right").orEmpty(), grow)
            "list" -> Block.ListRows(ref(n.text("list"), at, "list", problems), n.path("max").asInt(3).coerceIn(1, 8), grow)
            "bars" -> Block.Bars(ref(n.text("bars"), at, "bars", problems), n.path("labels").asBoolean(true), n.path("height").asInt(44).coerceIn(20, 120), grow)
            "checklist" -> Block.Checklist(ownKey(n.text("checklist")), n.path("max").asInt(5).coerceIn(1, OwnData.MAX_ITEMS), grow)
            "dots" -> Block.Dots(n.path("dots").asText(""), n.path("of").asInt(0), grow)
            "timer" -> Block.Timer(ownKey(n.text("timer")), grow)
            "button" -> Block.Button(
                n.path("button").asText(""),
                when (n.text("icon")) { "mic" -> ButtonIcon.MIC; "check" -> ButtonIcon.CHECK; else -> ButtonIcon.TEXT },
                ref(n.text("do"), at, "button 的 do", problems), grow
            )
            else -> Block.Rule(grow)
        }
    }

    private fun ref(text: String?, at: Int, what: String, problems: MutableList<String>): Ref =
        Ref.parse(text) ?: run { problems += "第 $at 块 $what 要写一个名字，比如 reminder.today、own.add cups 1"; Ref("?", "?") }

    private fun flagRef(text: String, at: Int, problems: MutableList<String>): Ref? =
        Ref.parse(text) ?: run { problems += "第 $at 块 accent_when / muted_when 要写一个是 / 否的名字，比如 own.cups.full"; null }

    /** 「own.items」「{{own.items}}」「items」都认成 items */
    private fun ownKey(text: String?): String =
        text?.trim()?.removeSurrounding("{{", "}}")?.trim()?.removePrefix("$OWN.").orEmpty()

    private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    // ---------------- 查

    private fun verify(spec: CardSpec, v: Vocabulary, problems: MutableList<String>) {
        if (spec.body.count() > MAX_BLOCKS) problems += "零件太多了：最多 $MAX_BLOCKS 个，现在 ${spec.body.count()} 个"
        if (spec.body.depth() > MAX_DEPTH) problems += "套得太深了：column / row 最多套 ${MAX_DEPTH - 1} 层"
        val counter = intArrayOf(0)

        fun need(ref: Ref?, at: Int, what: String, vararg kinds: CardKind) {
            ref ?: return
            if (!v.knows(ref.source)) { problems += "第 $at 块 $what：没有 ${ref.source} 这一类，能绑的见说明"; return }
            val f = v.field(ref, spec.own) ?: run {
                problems += if (ref.source == OWN) "第 $at 块 $what：own 里没有 ${ref.name}（own 里定义了：${spec.own.keys.joinToString("、").ifEmpty { "没有" }}）"
                else "第 $at 块 $what：${ref.source} 没有 ${ref.name}"
                return
            }
            if (ref.args.size !in f.minArgs..f.maxArgs) problems += "第 $at 块 $what：${ref.source}.${f.name} 的参数是「${f.args.ifEmpty { "不要参数" }}」"
            if (kinds.isNotEmpty() && f.kind !in kinds) problems += "第 $at 块 $what：${ref.source}.${f.name} 是${f.kind.label}，这里要${kinds.joinToString(" 或 ") { it.label }}"
        }

        fun template(text: String, at: Int, what: String) =
            Ref.holes(text).forEach { h -> Ref.parse(h)?.let { need(it, at, what) } ?: run { problems += "第 $at 块 $what 里的 {{$h}} 写不成名字" } }

        /** 整段是一个绑定就查它的种类，不是绑定就得是字面数 */
        fun numberLike(text: String, at: Int, what: String, vararg kinds: CardKind) {
            val whole = Ref.whole(text)
            when {
                whole != null -> need(whole, at, what, *kinds)
                Ref.holes(text).isNotEmpty() -> problems += "第 $at 块 $what 只能是一个名字 {{…}} 或者一个数，不能和字拼"
                text.trim().toLongOrNull() == null && CardKind.TEXT !in kinds -> problems += "第 $at 块 $what 要是一个数或 {{名字}}"
            }
        }

        fun paint(p: Paint, at: Int) {
            need(p.accentWhen, at, "accent_when", CardKind.FLAG)
            need(p.mutedWhen, at, "muted_when", CardKind.FLAG)
        }

        fun walk(b: Block) {
            val at = ++counter[0]
            when (b) {
                is Block.Column -> b.children.forEach(::walk)
                is Block.Row -> b.children.forEach(::walk)
                is Block.Text -> { template(b.text, at, "text"); paint(b.paint, at) }
                is Block.Number -> { numberLike(b.value, at, "number", CardKind.NUMBER, CardKind.TEXT); paint(b.paint, at) }
                is Block.Bar -> numberLike(b.value, at, "bar", CardKind.NUMBER)
                is Block.ListRows -> need(b.rows, at, "list", CardKind.ROWS)
                is Block.Bars -> need(b.values, at, "bars", CardKind.SERIES)
                is Block.Checklist -> if (spec.own[b.key] !is OwnDef.Checklist) problems += "第 $at 块 checklist 要写 own 里一张 list 的键"
                is Block.Dots -> {
                    if (b.of !in 1..12) problems += "第 $at 块 dots 的 of 是 1 到 12"
                    numberLike(b.value, at, "dots", CardKind.NUMBER)
                }
                is Block.Timer -> if (spec.own[b.key] !is OwnDef.Timer) problems += "第 $at 块 timer 要写 own 里一个 timer 的键"
                is Block.Button -> {
                    val a = b.action
                    when {
                        !v.knows(a.source) -> problems += "第 $at 块 button 的 do：没有 ${a.source} 这一类"
                        v.action(a) == null -> problems += "第 $at 块 button 的 do：${a.source} 没有 ${a.name} 这个动作"
                        a.source == OWN && spec.own[a.args.firstOrNull()] == null -> problems += "第 $at 块 button 的 do：own 里没有 ${a.args.firstOrNull() ?: "（没写键）"}"
                    }
                    if (b.icon == ButtonIcon.TEXT && b.label.isBlank()) problems += "第 $at 块 button 要写字，或者 icon 写 mic / check"
                }
                is Block.Rule -> Unit
            }
        }
        walk(spec.body)
    }
}

package com.abc.daodian.card.spec

import com.abc.daodian.card.plugin.CardField
import com.abc.daodian.card.plugin.CardKind
import com.abc.daodian.card.plugin.CardSource
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 卡片说明的 JSON ↔ [CardSpec]，和那道闸门（§8.5）：零件认不认识、绑的名字有没有、绑上的值合不合这个零件、按钮的动作有没有，
 * 再加 11-16 定的五条：数字类零件只认绑定、至少绑一条你的记录（只绑 time 的不收）、字当标签用（这条闸门拦不全，写在说明里）、
 * 按钮只认模块交的动作、按大小限零件数。
 * 拦下的原因写成模型能照着改的话（「第 3 块 number 绑的 reminder.title 是字，不是数」），由它自己改了再交。
 *
 * 写法（工具包说明里给模型的那一份在 `assistant/CardGuide`，两边一起改）：
 * ```
 * {"name":"去旧金山","size":"2x2","body":{"column":[{"text":"去旧金山","style":"title"},
 *   {"number":"{{reminder.days_left #12}}","after":"天","grow":true},{"text":"{{reminder.date #12}}","style":"caption"}]}}
 * ```
 */
object CardSpecs {

    /** 最多套几层 */
    const val MAX_DEPTH = 5

    /** 只跟日历有关的那一类：不算「你的记录」（闸门第 2 条） */
    const val TIME = "time"

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
        val name = root.text("name") ?: run { problems += "name 要写（几个字：「去旧金山」）"; "" }
        val size = CardSize.of(root.text("size")) ?: run {
            problems += "size 只能是 ${CardSize.entries.joinToString("、") { it.key }}"; CardSize.S2X2
        }
        if (root.has("own")) problems += "卡片不自己记数，没有 own：每个数都绑提醒或记账里的那条；还没有就先建记录"
        val bodyNode = root.path("body")
        if (bodyNode.isMissingNode) { problems += "body 要写（最外面是 column 或 row）"; return null }
        val counter = intArrayOf(0)
        val body = block(bodyNode, problems, counter) ?: return null
        if (body !is Block.Column && body !is Block.Row) problems += "body 最外面要是 column 或 row"
        return CardSpec(name.trim().take(12), size, body)
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
            "checklist" -> Block.Checklist(ref(n.text("checklist"), at, "checklist", problems), n.path("max").asInt(5).coerceIn(1, 12), grow)
            "dots" -> Block.Dots(n.path("dots").asText(""), n.path("of").asInt(0), grow)
            "timer" -> Block.Timer(ref(n.text("timer"), at, "timer", problems), grow)
            "button" -> Block.Button(
                n.path("button").asText(""),
                when (n.text("icon")) { "mic" -> ButtonIcon.MIC; "check" -> ButtonIcon.CHECK; else -> ButtonIcon.TEXT },
                ref(n.text("do"), at, "button 的 do", problems), grow
            )
            else -> Block.Rule(grow)
        }
    }

    private fun ref(text: String?, at: Int, what: String, problems: MutableList<String>): Ref =
        Ref.parse(text) ?: run { problems += "第 $at 块 $what 要写一个名字，比如 {{reminder.items #12}}"; Ref("?", "?") }

    private fun flagRef(text: String, at: Int, problems: MutableList<String>): Ref? =
        Ref.parse(text) ?: run { problems += "第 $at 块 accent_when / muted_when 要写一个是 / 否的名字，比如 {{reminder.near #12}}"; null }

    private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    // ---------------- 查

    private fun verify(spec: CardSpec, v: Vocabulary, problems: MutableList<String>) {
        val blocks = spec.body.count()
        // 第 5 条：按大小限零件数
        if (blocks > spec.size.maxBlocks) problems += "零件太多了：${spec.size.label} 最多 ${spec.size.maxBlocks} 个（排法也算），现在 $blocks 个。放多了会被卡片边裁掉，删几个"
        if (spec.body.depth() > MAX_DEPTH) problems += "套得太深了：column / row 最多套 ${MAX_DEPTH - 1} 层"
        // 第 2 条：至少绑一条你的记录
        val sources = (spec.body.bindings() + spec.body.actions()).map { it.source }.filter { v.knows(it) }
        if (sources.none { it != TIME }) {
            val records = v.sources.filter { it.id != TIME }.joinToString("或") { it.label }
            problems += "卡片至少要绑一条${records}里的记录（只绑 time 的不收）。要数的事还没记下，先建一条再画"
        }
        val counter = intArrayOf(0)

        fun need(ref: Ref?, at: Int, what: String, vararg kinds: CardKind) {
            ref ?: return
            if (!v.knows(ref.source)) { problems += "第 $at 块 $what：没有 ${ref.source} 这一类，能绑的见说明"; return }
            val f = v.field(ref) ?: run { problems += "第 $at 块 $what：${ref.source} 没有 ${ref.name}"; return }
            if (ref.args.size !in f.minArgs..f.maxArgs) problems += "第 $at 块 $what：${ref.source}.${f.name} 的参数是「${f.args.ifEmpty { "不要参数" }}」"
            if (kinds.isNotEmpty() && f.kind !in kinds) problems += "第 $at 块 $what：${ref.source}.${f.name} 是${f.kind.label}，这里要${kinds.joinToString(" 或 ") { it.label }}"
        }

        fun template(text: String, at: Int, what: String) =
            Ref.holes(text).forEach { h -> Ref.parse(h)?.let { need(it, at, what) } ?: run { problems += "第 $at 块 $what 里的 {{$h}} 写不成名字" } }

        /** 第 1 条：数字类零件只认一个绑定，不能填数、不能和字拼 */
        fun bound(text: String, at: Int, what: String, vararg kinds: CardKind) {
            val whole = Ref.whole(text)
            if (whole != null) need(whole, at, what, *kinds)
            else problems += "第 $at 块 $what 只能是一个名字 {{…}}，不能填数、不能和字拼：数由代码现取，不抄"
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
                is Block.Number -> {
                    bound(b.value, at, "number", CardKind.NUMBER, CardKind.TEXT)
                    template(b.before + b.after, at, "number 的 before / after")
                    paint(b.paint, at)
                }
                is Block.Bar -> { bound(b.value, at, "bar", CardKind.NUMBER); template(b.left + b.right, at, "bar 的 left / right") }
                is Block.ListRows -> need(b.rows, at, "list", CardKind.ROWS)
                is Block.Bars -> need(b.values, at, "bars", CardKind.SERIES)
                is Block.Checklist -> need(b.rows, at, "checklist", CardKind.CHECKS)
                is Block.Dots -> {
                    if (b.of !in 1..12) problems += "第 $at 块 dots 的 of 是 1 到 12"
                    bound(b.value, at, "dots", CardKind.NUMBER)
                }
                is Block.Timer -> need(b.at, at, "timer", CardKind.MOMENT)
                is Block.Button -> {
                    // 第 4 条：只认模块交的动作
                    val a = b.action
                    when {
                        !v.knows(a.source) -> problems += "第 $at 块 button 的 do：没有 ${a.source} 这一类"
                        v.action(a) == null -> problems += "第 $at 块 button 的 do：${a.source} 没有 ${a.name} 这个动作"
                        a.args.size !in v.action(a)!!.minArgs..v.action(a)!!.maxArgs ->
                            problems += "第 $at 块 button 的 do：${a.source}.${a.name} 的参数是「${v.action(a)!!.args.ifEmpty { "不要参数" }}」"
                    }
                    template(b.label, at, "button")
                    if (b.icon == ButtonIcon.TEXT && b.label.isBlank()) problems += "第 $at 块 button 要写字，或者 icon 写 mic / check"
                }
                is Block.Rule -> Unit
            }
        }
        walk(spec.body)
    }
}

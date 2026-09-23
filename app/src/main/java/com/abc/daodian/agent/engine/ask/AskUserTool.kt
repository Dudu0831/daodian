package com.abc.daodian.agent.engine.ask

import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.engine.tool.ToolEffect
import com.abc.daodian.agent.engine.tool.ToolOutcome
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory

/**
 * 拿不准的时候问用户：先猜好答案，他点一下就行，也可以自己写或者直接说一句。见 DESIGN.md §6.9
 *
 * 执行时挂起，直到 [ToolContext.asker] 交回答案 —— 一轮对话会在这里停住等人，可能停很久。
 * 回给模型的结果末尾多一行 `answer=…`（机读），重启后把问卡原样画回来靠它。
 */
class AskUserTool : Tool {

    override val name = NAME

    override val effect = ToolEffect.READ

    override val description =
        "拿不准的时候问用户。先替他把最可能的答案猜好（每题 1–3 个），他点一下就行，也可以自己写、或者直接说一句。" +
            "一张卡最多 $MAX_QUESTIONS 题，要问的多就分几次。用户答完才返回，结果里是每一题的答案。" +
            "要问就用它，不要在正文里问。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(
            "label" to mapOf("type" to "string", "description" to "卡片左上角的短标签，2–6 个字：「4 笔」「交房租」「开会」"),
            "questions" to mapOf(
                "type" to "array",
                "description" to "1–$MAX_QUESTIONS 题",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to linkedMapOf(
                        "context" to nullable("出处，一行：「9月22日 11:02 · 建行 · 没有商户」。没有就 null"),
                        "amount" to nullable("问的是一笔钱就写金额「¥36.50」，否则 null"),
                        "question" to nullable("问句：「什么时候提醒？」。问账、有金额的可以 null"),
                        "hint" to nullable("第一个猜测的依据，一行：「前两天 11 点多也各有一笔三十来块，你都说是午饭」。没有像样的依据就 null"),
                        "options" to mapOf(
                            "type" to "array",
                            "description" to "1–$MAX_OPTIONS 个猜测，最可能的放第一个；真猜不出（比如金额都不知道）才给空数组，他会自己写。不要放「其他」「不知道」「跳过」—— 界面自带",
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to linkedMapOf(
                                    "label" to mapOf("type" to "string", "description" to "猜测本身，越短越好：「午饭」「每个月最后一天」"),
                                    "detail" to nullable("补一句：归到哪类「餐饮 · 堂食」，或时间「20:00 · 每月」。没有就 null")
                                ),
                                "required" to listOf("label", "detail"),
                                "additionalProperties" to false
                            )
                        )
                    ),
                    "required" to listOf("context", "amount", "question", "hint", "options"),
                    "additionalProperties" to false
                )
            )
        ),
        "required" to listOf("label", "questions"),
        "additionalProperties" to false
    )

    /** 没等到答案就被停掉：记成「没答」，重启后问卡画成「没答」而不是一直等着 */
    override val abortedOutput: String get() = UNANSWERED

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val request = requestOf(arguments)
            ?: return ToolOutcome("没问出去：参数不是合法的 JSON。", ok = false)
        problemOf(request)?.let { return ToolOutcome("没问出去：$it。改好再问。", ok = false) }

        val answer = context.asker.ask(context.call ?: Item.ToolCall("", NAME, arguments), request)
        return ToolOutcome(outputOf(request, answer), ok = answer !is AskAnswer.Unanswered, payload = answer)
    }

    companion object {
        const val NAME = "ask_user"
        const val MAX_QUESTIONS = 4
        const val MAX_OPTIONS = 3

        /** 没答时记进历史的那句。app 在等人的时候被杀了，重启后给悬着的调用补上它 */
        val UNANSWERED: String get() = outputOf(null, AskAnswer.Unanswered)

        private const val ANSWER_MARK = "answer="
        private val mapper = ObjectMapper()

        /** 参数 → 问卡。缺字段的题、空的猜测直接丢掉；解析不了是 null */
        fun requestOf(arguments: String): AskRequest? = runCatching {
            val o = mapper.readTree(arguments)
            AskRequest(
                label = o.text("label").orEmpty(),
                questions = o.path("questions").mapNotNull(::questionOf)
            )
        }.getOrNull()

        /**
         * 参数还在流的时候：已经收全的题先画出来，还没收全的那题不画（见动效稿「起卡」）。
         * 半截 JSON 解析不了，所以手扫到 `questions` 数组里，一个对象闭合了就解析一个。
         */
        fun draftOf(partial: String): AskRequest {
            val label = closedString(partial, "label").orEmpty()
            val start = partial.indexOf("\"questions\"").takeIf { it >= 0 }
                ?.let { partial.indexOf('[', it) }?.takeIf { it >= 0 }
                ?: return AskRequest(label, emptyList())
            val questions = mutableListOf<AskQuestion>()
            var depth = 0
            var from = -1
            var inString = false
            var i = start + 1
            while (i < partial.length) {
                val c = partial[i]
                when {
                    inString -> if (c == '\\') i++ else if (c == '"') inString = false
                    c == '"' -> inString = true
                    c == '{' || c == '[' -> { if (depth == 0 && c == '{') from = i; depth++ }
                    c == '}' || c == ']' -> {
                        if (depth == 0) break
                        depth--
                        if (depth == 0 && c == '}' && from >= 0) {
                            runCatching { questionOf(mapper.readTree(partial.substring(from, i + 1))) }.getOrNull()
                                ?.let(questions::add)
                            from = -1
                        }
                    }
                }
                i++
            }
            return AskRequest(label, questions)
        }

        /** 问卡不合规的地方，说给模型听；合规是 null */
        fun problemOf(r: AskRequest): String? = when {
            r.questions.isEmpty() -> "questions 是空的"
            r.questions.size > MAX_QUESTIONS -> "一张卡最多 $MAX_QUESTIONS 题，这次有 ${r.questions.size} 题，分几次问"
            r.questions.any { it.options.size > MAX_OPTIONS } -> "每题最多 $MAX_OPTIONS 个猜测"
            r.questions.any { it.amount == null && it.prompt == null && it.context == null } -> "每题至少要有 question、amount、context 之一"
            else -> null
        }

        /** 回给模型的话。第一段是人话，末行是机读的 `answer=…` */
        fun outputOf(request: AskRequest?, answer: AskAnswer): String {
            val node = JsonNodeFactory.instance.objectNode()
            val human = when (answer) {
                is AskAnswer.Picked -> {
                    val arr = node.putArray("picks")
                    buildString {
                        append("用户答了：")
                        answer.picks.forEachIndexed { i, pick ->
                            val q = request?.questions?.getOrNull(i)
                            append('\n').append(i + 1).append(". ").append(q?.let(::nameOf) ?: "第 ${i + 1} 题").append(" → ")
                            when (pick) {
                                null -> { arr.addNull(); append("没答，先放着") }
                                is Pick.Option -> {
                                    arr.addObject().put("option", pick.index)
                                    val o = q?.options?.getOrNull(pick.index)
                                    append("选了「").append(o?.label ?: "第 ${pick.index + 1} 个").append("」")
                                    o?.detail?.let { append("（").append(it).append("）") }
                                }
                                is Pick.Typed -> { arr.addObject().put("typed", pick.text); append("自己写了「").append(pick.text).append("」") }
                            }
                        }
                    }
                }
                is AskAnswer.Said -> {
                    node.put("said", answer.text)
                    "用户没点选项，直接说：「${answer.text}」\n按这句话去对应各题；对不上的当没答。"
                }
                AskAnswer.Unanswered -> {
                    node.put("unanswered", true)
                    "用户没有回答（叫停了，或者关掉了）。别换个说法再问同样的问题，除非他自己提起。"
                }
            }
            return human + "\n" + ANSWER_MARK + node.toString()
        }

        /** 从回给模型的结果里读回答案，重建问卡用。认不出是 null */
        fun answerOf(output: String): AskAnswer? = runCatching {
            val line = output.lineSequence().lastOrNull { it.startsWith(ANSWER_MARK) } ?: return null
            val o = mapper.readTree(line.removePrefix(ANSWER_MARK))
            when {
                o.has("picks") -> AskAnswer.Picked(o.path("picks").map { p ->
                    when {
                        p.has("option") -> Pick.Option(p.path("option").asInt())
                        p.has("typed") -> Pick.Typed(p.path("typed").asText())
                        else -> null
                    }
                })
                o.has("said") -> AskAnswer.Said(o.path("said").asText())
                else -> AskAnswer.Unanswered
            }
        }.getOrNull()

        /** 一题在人话结果里的叫法：「¥36.50（9月22日 11:02 · 建行）」「什么时候提醒？」 */
        private fun nameOf(q: AskQuestion): String = when {
            q.amount != null -> q.amount + (q.context?.let { "（$it）" } ?: "")
            q.prompt != null -> q.prompt
            else -> q.context.orEmpty()
        }

        private fun questionOf(n: JsonNode): AskQuestion? {
            if (!n.isObject) return null
            val options = n.path("options").mapNotNull { o ->
                o.text("label")?.let { AskOption(it, o.text("detail")) }
            }
            return AskQuestion(n.text("context"), n.text("amount"), n.text("question"), n.text("hint"), options)
        }

        private fun JsonNode.text(field: String): String? =
            get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

        private fun closedString(raw: String, key: String): String? {
            val m = Regex("\"$key\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")").find(raw) ?: return null
            return runCatching { mapper.readValue(m.groupValues[1], String::class.java) }.getOrNull()
        }

        private fun nullable(desc: String) = mapOf("type" to listOf("string", "null"), "description" to desc)
    }
}

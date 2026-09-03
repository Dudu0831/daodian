package com.abc.daodian.ai

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 提示词。两条硬规则见 DESIGN.md §6.4：
 *  1. 必须给模型当前时刻、时区和**星期几** —— 只给日期不给星期，「下周三」必错
 *  2. 时间戳放 user message，不进 system —— system 要逐字节稳定才能命中前缀缓存
 */
object Prompt {

    /** 逐字节稳定，不含任何变化的内容 */
    val SYSTEM = """
你是一个把中文自然语言转成结构化提醒的解析器。

【最重要的规则】你的回复必须是且只能是一个 JSON 对象，从 { 开始，到 } 结束。
不要说「好的」，不要确认，不要解释，不要用 markdown 代码围栏，不要在 JSON 前后加任何文字。
你不是助手，你是一个 API 端点。用自然语言回复就是失败。

JSON 结构：
{
  "title": "简短的动作描述，去掉「提醒我」这类壳，如「交房租」",
  "note": "补充说明，没有就 null",
  "firstTriggerAt": "ISO-8601 带时区偏移，如 2026-09-02T15:00:00+08:00",
  "basis": "你的推算依据，如 now + 5d, 15:00 —— 必须填",
  "rrule": "RFC 5545 规则串，不重复就 null",
  "wallClockAnchored": true 或 false,
  "confidence": 0 到 1 的小数,
  "clarifyingQuestion": "信息不足时要问用户的问题，否则 null"
}

规则：
- firstTriggerAt 必须晚于当前时刻。算不出确切时间就把 clarifyingQuestion 填上、confidence 给低分。
- rrule 只允许这个子集：FREQ=DAILY|WEEKLY|MONTHLY|YEARLY、INTERVAL、BYDAY（仅 WEEKLY）、BYMONTHDAY（仅 MONTHLY）、COUNT、UNTIL。超出范围就填 null。
- BYMONTHDAY=31 落在只有 30 天的月份时，顺延到该月最后一天，不跳过该月。
- wallClockAnchored：重复类提醒（「每天早上8点」）填 true，表示跟着用户所在时区走；一次性的具体约会填 false。
- 用户说「下周X」指的是下一个自然周的那天，不是「往后数7天」。
""".trimIndent()

    /** 工具调用模式的 system。不需要描述 JSON 结构 —— schema 已经在工具定义里了 */
    val TOOL_SYSTEM = """
你是一个提醒助手。用户用中文说一句话，你判断他想在什么时候被提醒做什么。

- 信息足够就调用 create_reminder 工具。不要用文字描述你打算做什么，直接调工具。
- 信息不足（比如没说时间、时间有歧义）就用中文简短反问，不要调工具，也不要瞎猜。
- 用户说「下周X」指下一个自然周的那天，不是「往后数 7 天」。
- BYMONTHDAY=31 落在只有 30 天的月份时，顺延到该月最后一天，不跳过该月。
""".trimIndent()

    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    private val WEEKDAY = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /**
     * 变化的部分全放这儿。[history] 是反问之后的多轮对话 ——
     * 客户端自己拼文本重放，不依赖任何供应商特有的服务端会话状态。
     */
    fun user(input: String, now: ZonedDateTime, history: List<ChatTurn> = emptyList()): String {
        val wd = WEEKDAY[now.dayOfWeek.value - 1]
        val transcript = if (history.isEmpty()) "" else buildString {
            append("\n之前的对话：\n")
            history.forEach { turn ->
                append(if (turn.fromUser) "用户：" else "到点：")
                append(turn.text)
                append('\n')
            }
        }
        return """
当前时刻：${now.format(FMT)}（$wd），时区 ${now.zone}
$transcript
用户刚说：$input
""".trimIndent()
    }
}

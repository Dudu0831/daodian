package com.abc.daodian.ai

import org.json.JSONObject

/**
 * 容错解析。见 DESIGN.md §6.2 ——
 * 三档 response_format 里最低那档完全没有服务端保证，模型可能包 markdown 围栏、
 * 前后带解释文字。这里一律按最脏的情况处理。
 *
 * 用 org.json 而不是 kotlinx.serialization：它在 Android 框架里，零依赖零包体。
 */
object JsonExtract {

    fun toPlan(raw: String): ReminderPlan? {
        val obj = firstObject(raw) ?: return null
        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
        val at = obj.optString("firstTriggerAt").takeIf { it.isNotBlank() } ?: return null
        return ReminderPlan(
            title = title,
            note = obj.optStringOrNull("note"),
            firstTriggerAt = at,
            basis = obj.optString("basis", ""),
            rrule = obj.optStringOrNull("rrule"),
            wallClockAnchored = obj.optBoolean("wallClockAnchored", true),
            confidence = obj.optDouble("confidence", 1.0).let { if (it.isNaN()) 1.0 else it },
            clarifyingQuestion = obj.optStringOrNull("clarifyingQuestion")
        )
    }

    /** 剥围栏 → 取第一个 { 到最后一个 } */
    private fun firstObject(raw: String): JSONObject? {
        val stripped = raw
            .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
            .trim()
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(stripped.substring(start, end + 1)) }.getOrNull()
    }

    /** org.json 会把 JSON null 变成字符串 "null"，得挡掉 */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }
}

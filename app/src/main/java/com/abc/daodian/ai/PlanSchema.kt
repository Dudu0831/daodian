package com.abc.daodian.ai

import com.openai.core.JsonValue

/**
 * ReminderPlan 的 JSON Schema。见 DESIGN.md §6.2 的 STRICT_SCHEMA 档。
 *
 * json_object 档只保证「返回的是合法 JSON」，不保证字段名对得上 ——
 * 实测模型会自己发明 {summary, details:{...}} 这种结构。要锁死字段名只能上 schema。
 */
object PlanSchema {

    const val NAME = "reminder_plan"

    private fun str(desc: String) = JsonValue.from(
        mapOf("type" to listOf("string", "null"), "description" to desc)
    )

    private fun strRequired(desc: String) = JsonValue.from(
        mapOf("type" to "string", "description" to desc)
    )

    /** strict 模式要求 required 覆盖所有 property，且 additionalProperties=false */
    val PROPERTIES: Map<String, JsonValue> = linkedMapOf(
        "title" to strRequired("简短动作，去掉「提醒我」这类壳，如「喝水」"),
        "note" to str("补充说明，没有就 null"),
        "firstTriggerAt" to strRequired("ISO-8601 带时区偏移，如 2026-09-03T19:40:00+08:00"),
        "basis" to strRequired("推算依据，如 now + 3min"),
        "rrule" to str("RFC 5545 规则串，不重复就 null"),
        "wallClockAnchored" to JsonValue.from(mapOf("type" to "boolean")),
        "confidence" to JsonValue.from(mapOf("type" to "number")),
        "clarifyingQuestion" to str("信息不足时要问用户的问题，否则 null")
    )

    val REQUIRED: List<String> = PROPERTIES.keys.toList()

    fun asMap(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to PROPERTIES.mapValues { (_, v) -> v },
        "required" to REQUIRED,
        "additionalProperties" to false
    )
}

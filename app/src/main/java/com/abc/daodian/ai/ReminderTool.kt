package com.abc.daodian.ai

import com.openai.core.JsonValue
import com.openai.models.responses.FunctionTool

/**
 * 「建一条提醒」这个工具。
 *
 * 比让模型输出 JSON 强的地方：参数 schema 由服务端强制，模型不能自己发明字段名
 * （实测 json_object 档下它会返回 {summary, details:{...}}）。
 * 而且语义上本来就对 —— 建提醒就是一次函数调用。
 *
 * 模型信息不够时可以**不调工具**，直接用自然语言反问，这就天然形成了澄清回合。
 */
object ReminderTool {

    const val NAME = "create_reminder"

    private fun s(desc: String) = JsonValue.from(mapOf("type" to "string", "description" to desc))
    private fun sNullable(desc: String) =
        JsonValue.from(mapOf("type" to listOf("string", "null"), "description" to desc))

    private val PROPERTIES: Map<String, JsonValue> = linkedMapOf(
        "title" to s("简短动作，去掉「提醒我」这类壳。例如「喝水」「交房租」"),
        "firstTriggerAt" to s("首次触发时刻，ISO-8601 带时区偏移，如 2026-09-03T19:40:00+08:00"),
        "basis" to s("你的推算依据，如 now + 3min。必须填，方便用户核对你有没有算错"),
        "note" to sNullable("补充说明，没有就 null"),
        "rrule" to sNullable(
            "重复规则 RFC 5545 子集，不重复就 null。只允许 FREQ/INTERVAL/BYDAY/BYMONTHDAY/COUNT/UNTIL"
        ),
        "wallClockAnchored" to JsonValue.from(
            mapOf(
                "type" to "boolean",
                "description" to "重复类提醒（每天早上8点）填 true，跟着用户所在时区走；一次性具体约会填 false"
            )
        )
    )

    /** strict 模式要求 required 覆盖全部字段、且 additionalProperties=false */
    private val REQUIRED = PROPERTIES.keys.toList()

    fun definition(): FunctionTool =
        FunctionTool.builder()
            .name(NAME)
            .description(
                "为用户创建一条定时提醒 —— 这会在他手机上真的排一个闹钟。" +
                    "只有当他明确要求被提醒、且触发时刻能算得出来时才调用。" +
                    "闲聊、提问、只是话里提到某个时间，都不要调。"
            )
            .strict(true)
            .parameters(
                FunctionTool.Parameters.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty("properties", JsonValue.from(PROPERTIES))
                    .putAdditionalProperty("required", JsonValue.from(REQUIRED))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build()
            )
            .build()

    /** 把模型给的 arguments JSON 串转成 ReminderPlan */
    fun toPlan(argumentsJson: String): ReminderPlan? = JsonExtract.toPlan(argumentsJson)
}

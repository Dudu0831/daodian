package com.abc.daodian.assistant.memory

import com.abc.daodian.runtime.memory.MemoryEdit
import com.abc.daodian.runtime.memory.MemoryRules
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** edit_memory、save_tidy 共用的参数写法 */
internal object MemoryJson {

    val mapper = ObjectMapper()

    val EDIT_PROPERTIES: Array<Pair<String, Any?>> = arrayOf(
        "add" to mapOf(
            "type" to "array",
            "description" to "新记的，一条一句不带主语的事实（「『晚点』一般指晚上 9 点」「每周二、周四晚上健身」），${MemoryRules.MAX_LENGTH} 字以内。没有就空数组",
            "items" to mapOf("type" to "string")
        ),
        "update" to mapOf(
            "type" to "array",
            "description" to "要改的已有记忆（m 编号的数字）和改成什么。没有就空数组",
            "items" to mapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "id" to mapOf("type" to "integer", "description" to "m 编号的数字：m3 写 3"),
                    "text" to mapOf("type" to "string", "description" to "改成的整句")
                ),
                "required" to listOf("id", "text"),
                "additionalProperties" to false
            )
        ),
        "remove" to mapOf(
            "type" to "array",
            "description" to "要忘掉的 m 编号的数字。没有就空数组",
            "items" to mapOf("type" to "integer")
        )
    )

    fun parse(arguments: String): JsonNode? = runCatching { mapper.readTree(arguments) }.getOrNull()?.takeIf { it.isObject }

    fun editOf(o: JsonNode): MemoryEdit = MemoryEdit(
        add = o.path("add").mapNotNull { n -> n.asText().trim().takeIf { it.isNotEmpty() } },
        update = o.path("update").filter { it.isObject && it.path("id").canConvertToLong() }
            .associate { it.path("id").asLong() to it.path("text").asText().trim() },
        remove = o.path("remove").filter { it.canConvertToLong() }.mapTo(LinkedHashSet()) { it.asLong() }
    )
}

package com.abc.daodian.agent.memory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 记下的关于用户的一件事，写成不带主语的一句：「『晚点』一般指晚上 9 点」。见 DESIGN.md §6.9
 *
 * 记的是**这个人**（作息、说法指什么、偏好、常提的人和地方），不是某一条提醒、某一笔账 —— 那些在各自的库里，
 * 会被改、被删，抄一份进记忆只会过时。认账用得上的说法（哪张卡是谁在用）算这个人的事，后台整理账目也读。
 */
data class Memory(
    val id: Long,
    val text: String,
    /** 记下或最后一次改的时刻（毫秒） */
    val updatedAt: Long,
    /** 从哪来的：[SAID] / [TIDY] / [USER] */
    val source: String,
    /** 第一次记下的时刻（毫秒）。和 [updatedAt] 不一样就是改过 */
    val createdAt: Long = updatedAt
) {
    companion object {
        /** 他在对话里让记的（edit_memory） */
        const val SAID = "said"
        /** 后台整理时从对话里看出来的 */
        const val TIDY = "tidy"
        /** 他自己在记忆页写的 */
        const val USER = "user"
    }
}

/** 一次改动：新记几条、改几条、忘几条。对话里的 edit_memory 和后台整理用同一种 */
data class MemoryEdit(
    val add: List<String> = emptyList(),
    val update: Map<Long, String> = emptyMap(),
    val remove: Set<Long> = emptySet()
) {
    val isEmpty: Boolean get() = add.isEmpty() && update.isEmpty() && remove.isEmpty()
}

/** 落下之后实际变了什么，给工具写结果、给痕写字 */
data class MemoryApplied(
    val added: List<Memory> = emptyList(),
    val updated: List<Memory> = emptyList(),
    val removed: List<Memory> = emptyList()
) {
    val count: Int get() = added.size + updated.size + removed.size

    /** 回给模型、也给痕拆行用：一行一条，`+` 新记、`~` 改了、`-` 忘了 */
    fun lines(): List<String> =
        added.map { "+ m${it.id} ${it.text}" } + updated.map { "~ m${it.id} ${it.text}" } + removed.map { "- m${it.id} ${it.text}" }
}

/** 记忆的存取。对话工具和后台整理都经它写；实现在 [MemoryBook]，测试里是假的 */
interface MemoryBackend {
    suspend fun all(): List<Memory>

    /** [edit] 已经过了 [MemoryRules.problemOf]。落的时候 id 已经不在的（别处刚删了）跳过 */
    suspend fun apply(edit: MemoryEdit, source: String): MemoryApplied
}

/** 记忆的规矩，落库前代码查一遍（和提醒的校验闸门、账的护栏一个意思） */
object MemoryRules {

    /** 最多记这么多条。满了先合并、删掉不要紧的 */
    const val MAX_COUNT = 40

    /** 一条最多几个字 */
    const val MAX_LENGTH = 60

    /** 连着这么多位数字，多半是卡号、证件号 */
    private val LONG_DIGITS = Regex("\\d{15,}")

    /** 不合规返回原因（写给模型看，它照着改），合规是 null */
    fun problemOf(edit: MemoryEdit, current: List<Memory>): String? {
        val ids = current.mapTo(HashSet()) { it.id }
        val texts = edit.add + edit.update.values
        texts.firstOrNull { it.isBlank() }?.let { return "有一条是空的" }
        texts.firstOrNull { it.length > MAX_LENGTH }?.let { return "「${it.take(12)}…」太长（${it.length} 字），一条 $MAX_LENGTH 字以内" }
        texts.firstOrNull { LONG_DIGITS.containsMatchIn(it) || it.contains("密码") }
            ?.let { return "「${it.take(12)}…」像是卡号、证件号或密码，这类不记" }
        (edit.update.keys + edit.remove).firstOrNull { it !in ids }?.let { return "没有 m$it 这一条" }
        edit.update.keys.firstOrNull { it in edit.remove }?.let { return "m$it 又改又删，只能选一样" }
        val after = current.size + edit.add.size - edit.remove.size
        if (after > MAX_COUNT) return "记满了：这样会有 $after 条，最多 $MAX_COUNT 条。先合并相近的、删掉不要紧的"
        return null
    }
}

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

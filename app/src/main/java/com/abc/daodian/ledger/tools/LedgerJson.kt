package com.abc.daodian.ledger.tools

import com.abc.daodian.ledger.domain.AccountRef
import com.abc.daodian.ledger.domain.AccountType
import com.abc.daodian.ledger.domain.Direction
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 记账工具共用的 schema 积木和参数读法 */
internal object LedgerJson {

    val mapper = ObjectMapper()

    fun parse(arguments: String): JsonNode? = runCatching { mapper.readTree(arguments) }.getOrNull()

    // ---------------- schema ----------------

    fun obj(vararg props: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(*props),
        "required" to props.map { it.first },
        "additionalProperties" to false
    )

    fun str(desc: String) = mapOf("type" to "string", "description" to desc)
    fun strOrNull(desc: String) = mapOf("type" to listOf("string", "null"), "description" to desc)
    fun int(desc: String) = mapOf("type" to "integer", "description" to desc)
    fun intOrNull(desc: String) = mapOf("type" to listOf("integer", "null"), "description" to desc)
    fun num(desc: String) = mapOf("type" to "number", "description" to desc)
    fun bool(desc: String) = mapOf("type" to "boolean", "description" to desc)
    fun enumOf(desc: String, values: List<String>) = mapOf("type" to "string", "enum" to values, "description" to desc)
    fun enumOrNull(desc: String, values: List<String>) =
        mapOf("type" to listOf("string", "null"), "enum" to values + listOf(null), "description" to desc)
    fun arr(desc: String, items: Map<String, Any?>) = mapOf("type" to "array", "items" to items, "description" to desc)
    fun arrOrNull(desc: String, items: Map<String, Any?>) =
        mapOf("type" to listOf("array", "null"), "items" to items, "description" to desc)
    fun objOrNull(desc: String, vararg props: Pair<String, Any?>): Map<String, Any?> =
        obj(*props) + mapOf("type" to listOf("object", "null"), "description" to desc)

    val DIRECTIONS = Direction.entries.map { it.name }
    val ACCOUNT_TYPES = AccountType.entries.map { it.name }

    val ACCOUNT = objOrNull(
        "付款 / 收款账户。通知里看不出来就 null",
        "bank" to str("哪家：建设银行 / 招商银行 / 支付宝 …"),
        "tail" to strOrNull("卡尾号，4 位；没有就 null"),
        "type" to enumOf("DEBIT 储蓄卡 / CREDIT 信用卡 / HUABEI 花呗 / BALANCE 余额", ACCOUNT_TYPES)
    )

    // ---------------- 读参数 ----------------

    fun JsonNode.text(field: String): String? =
        get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    fun JsonNode.longs(field: String): List<Long> =
        get(field)?.takeIf { it.isArray }?.mapNotNull { n -> n.takeIf { it.canConvertToLong() }?.asLong() }.orEmpty()

    fun JsonNode.strings(field: String): List<String> =
        get(field)?.takeIf { it.isArray }?.mapNotNull { it.asText()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

    fun JsonNode.longOrNull(field: String): Long? =
        get(field)?.takeIf { !it.isNull && it.canConvertToLong() }?.asLong()

    fun JsonNode.objects(field: String): List<JsonNode> =
        get(field)?.takeIf { it.isArray }?.filter { it.isObject }.orEmpty()

    fun JsonNode.account(): AccountRef? {
        val a = get("account")?.takeIf { it.isObject } ?: return null
        val bank = a.text("bank") ?: return null
        val type = AccountType.of(a.text("type")) ?: return null
        return AccountRef(bank, a.text("tail")?.takeLast(4), type)
    }

    // ---------------- 时间 ----------------

    /** ISO 时刻，带不带偏移都认；不带的按 [zone] */
    fun instant(text: String?, zone: ZoneId): Long? {
        if (text.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(text.take(10)).atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli() }.getOrNull()
    }

    /** 「2026-09-22」→ 20260922 */
    fun dayOf(text: String?): Int? = runCatching { LocalDate.parse(text!!.trim().take(10)).let(::dayInt) }.getOrNull()

    fun dayInt(d: LocalDate): Int = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth

    fun dayOf(millis: Long, zone: ZoneId): Int = dayInt(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun stamp(millis: Long, zone: ZoneId): String = STAMP.format(Instant.ofEpochMilli(millis).atZone(zone))
}

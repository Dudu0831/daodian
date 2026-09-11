package com.abc.daodian.ui.chat

import com.abc.daodian.ui.common.Format
import java.time.OffsetDateTime

/**
 * 从流到一半的工具参数里抠出草稿卡要的两个字段。见 DESIGN.md 决策 6.2 第 2 条。
 *
 * 只抠来画草稿，不拿来落库：落库用的仍是流结束时服务端给的完整参数（§6.7 坑 3）。
 * 参数是半截 JSON，JSON 库解析不了，所以手扫字符串。
 */
data class DraftArgs(val title: String, val whenText: String?) {

    val isEmpty: Boolean get() = title.isEmpty() && whenText == null

    companion object {
        val EMPTY = DraftArgs("", null)

        fun parse(raw: String): DraftArgs {
            if (raw.isEmpty()) return EMPTY
            val title = stringField(raw, "title")?.value.orEmpty()
            // 时间必须等整串收全：半截 ISO 串没法变成人话，宁可先留占位条
            val whenText = stringField(raw, "firstTriggerAt")
                ?.takeIf { it.closed }
                ?.let { runCatching { OffsetDateTime.parse(it.value) }.getOrNull() }
                ?.let { Format.humanDateTime(it.toInstant().toEpochMilli()) }
            return DraftArgs(title, whenText)
        }

        private class Field(val value: String, val closed: Boolean)

        /** 找 `"key": "…` 读出字符串值；还没读到收尾引号时 closed = false */
        private fun stringField(raw: String, key: String): Field? {
            val k = raw.indexOf("\"$key\"")
            if (k < 0) return null
            var i = raw.indexOf(':', k + key.length + 2)
            if (i < 0) return null
            i++
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length || raw[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < raw.length) {
                val c = raw[i]
                if (c == '"') return Field(sb.toString(), closed = true)
                if (c != '\\') {
                    sb.append(c)
                    i++
                    continue
                }
                // 转义符后面的字还没到，停在这儿等下一段
                if (i + 1 >= raw.length) break
                when (val e = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 6 > raw.length) break
                        raw.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 4
                    }
                    else -> sb.append(e)
                }
                i += 2
            }
            return Field(sb.toString(), closed = false)
        }
    }
}

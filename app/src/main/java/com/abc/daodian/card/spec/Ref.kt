package com.abc.daodian.card.spec

/**
 * 一个绑定：`reminder.days_left #7` = 归 reminder 管的 days_left，参数 #7。卡片模块自带的时间前缀是 time（`time.weekday`）。
 * 写在说明里时套一层花括号：`{{reminder.days_left #7}}`。不写算式（§8.5）：要「勾了几样」就让模块多交一个名字
 */
data class Ref(val source: String, val name: String, val args: List<String> = emptyList()) {

    override fun toString(): String = (listOf("$source.$name") + args).joinToString(" ")

    companion object {

        // 花括号全转义：安卓的正则（ICU）不认没转义的 }，JVM 认 —— 单元测试拦不住，真机上 Ref 加载就失败
        private val HOLE = Regex("\\{\\{\\s*([^\\{\\}]*?)\\s*\\}\\}")

        /** 「reminder.days_left #7」→ Ref；写不成绑定的是 null。花括号有没有都认 */
        fun parse(expr: String?): Ref? {
            val inner = expr?.trim()?.removeSurrounding("{{", "}}")?.trim() ?: return null
            if (inner.isEmpty() || inner.contains("{") || inner.contains("}")) return null
            val tokens = inner.split(Regex("\\s+"))
            val head = tokens.first()
            val source = head.substringBefore('.', "")
            val name = head.substringAfter('.', "")
            if (!SOURCE.matches(source) || name.isEmpty()) return null
            return Ref(source, name, tokens.drop(1))
        }

        /** 一段字里嵌了哪几个绑定 */
        fun holes(text: String): List<String> = HOLE.findAll(text).map { it.groupValues[1] }.toList()

        /** 整段就是一个绑定（`{{reminder.days_left #7}}`），不是就 null */
        fun whole(text: String): Ref? = text.trim().takeIf { HOLE.matchEntire(it) != null }?.let(::parse)

        /** 把字里的绑定换成 [value] 给的字 */
        suspend fun fill(text: String, value: suspend (Ref?) -> String): String {
            val out = StringBuilder()
            var last = 0
            HOLE.findAll(text).forEach { m ->
                out.append(text, last, m.range.first)
                out.append(value(parse(m.groupValues[1])))
                last = m.range.last + 1
            }
            out.append(text, last, text.length)
            return out.toString()
        }

        private val SOURCE = Regex("[a-z][a-z0-9_]*")
    }
}

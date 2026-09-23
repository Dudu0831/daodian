package com.abc.daodian.agent.feature

/**
 * 从流到一半的工具参数里抠字段。痕在办的时候逐字显示标题用（见 DESIGN.md 决策 6.2 第 2 条）。
 *
 * 只抠来画，不拿来落库：落库用的仍是流结束时服务端给的完整参数（§6.7 坑 3）。
 * 参数是半截 JSON，JSON 库解析不了，所以手扫字符串。
 */
object PartialArgs {

    /** 半截参数里某个字符串字段目前流到的样子（没流完也给） */
    fun text(raw: String, key: String): String? {
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
            if (c == '"') return sb.toString()
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
        return sb.toString()
    }
}

package com.abc.daodian.intake

import java.util.concurrent.ConcurrentHashMap

/**
 * 每个 app 最近发过通知的标题（整理过格式的：「老婆」不带「(3条)」），建「按人听」的规则时给助手挑 ——
 * 标题要一字不差（DESIGN.md §12.2 `recent_senders`）。**只在内存里、只记标题**，进程没了就清空，和以前派活的「最近发过消息的」一样。
 * 收通知的流水线（第 6 步接线后）每条都记一笔
 */
object RecentTitles {

    private const val MAX = 12

    private val byPkg = ConcurrentHashMap<String, List<String>>()

    fun note(pkg: String, title: String?) {
        val t = title?.trim()?.takeIf { it.isNotEmpty() } ?: return
        byPkg.compute(pkg) { _, old -> (listOf(t) + old.orEmpty()).distinct().take(MAX) }
    }

    /** 新的在前 */
    fun of(pkg: String): List<String> = byPkg[pkg].orEmpty()
}

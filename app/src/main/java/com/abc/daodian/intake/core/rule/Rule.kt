package com.abc.daodian.intake.core.rule

/**
 * 一条规则（DESIGN.md §12.1）：[hard] 代码判，只管进不进管家；[soft] 是一段话，管家读，交给谁写在这里。
 * 只在对话里建（§12.2）；停了不删（[enabled] 是 false），设置页上能撤销
 *
 * @param name 「快递到了提醒取」
 */
data class Rule(
    val id: Long,
    val name: String,
    val hard: AllOf,
    val soft: String,
    val enabled: Boolean,
    val createdAt: Long
) {
    /** 听哪几个 app。不听这个包名的规则，这个 app 的通知不用判 */
    val apps: Set<String> = hard.parts.filterIsInstance<AppIn>().map { it.pkgs }.reduceOrNull { a, b -> a intersect b }.orEmpty()

    fun watches(pkg: String): Boolean = pkg in apps
}

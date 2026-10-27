package com.abc.daodian.intake.core.steward

import com.abc.daodian.intake.core.inbox.InboxNotice
import com.abc.daodian.intake.core.rule.AppNames
import com.abc.daodian.intake.core.rule.Rule
import java.time.Instant
import java.time.ZoneId

/** 去处清单上的一项：模块交上来的 `NoticeTarget` 的那三样（核心不认插口，外面抄过来） */
data class Destination(val id: String, val label: String, val brief: String)

/**
 * 管家每一轮看到的（DESIGN.md §12.4），从稳定到常变排：去处清单 → 规则 → 记忆 → 这一批。
 * [standing] 是前两段，只在模块、规则变了时变，同样的东西拼出来逐字节一样（前缀缓存）；
 * 记忆由 runtime 垫，这一批是 [batch]。怎么读、怎么定写在管家的提示词里，这里只摆数据
 */
object StewardInput {

    /** @param destinations 照 `Modules.kt` 的顺序；[rules] 按编号排，传进来什么顺序都一样 */
    fun standing(destinations: List<Destination>, rules: List<Rule>): String = buildString {
        append("去处（hand_off 的 to 填 id）：\n")
        destinations.forEach { append("- ").append(it.id).append(" · ").append(it.label).append("：").append(it.brief.trim()).append('\n') }
        append("\n规则（编号 · 名字：软规则）：\n")
        rules.sortedBy { it.id }.forEach { append("- r").append(it.id).append(" · ").append(it.name.trim()).append("：").append(it.soft.trim()).append('\n') }
    }.trimEnd()

    /** 这一批，按编号排。遮蔽版不给正文，写明看不清 */
    fun batch(notices: List<InboxNotice>, names: AppNames, zone: ZoneId): String = buildString {
        append("这一批 ").append(notices.size).append(" 条：\n")
        notices.sortedBy { it.id }.forEach { n ->
            append("\nn").append(n.id).append(" · ").append(names.of(n.pkg)).append(" · ").append(time(n.at, zone))
            append(" · 中了 ").append(n.rules.sorted().joinToString("、") { "r$it" }).append('\n')
            n.title?.let { append("标题：").append(it).append('\n') }
            if (n.redacted) {
                append("正文：（被系统遮蔽了，看不清）\n")
            } else {
                n.body?.let { append("正文：").append(it).append('\n') }
                n.extra?.let { append("展开：").append(it).append('\n') }
            }
        }
    }.trimEnd()

    /** 「10月26日 17:42」 */
    private fun time(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone).let {
        "${it.monthValue}月${it.dayOfMonth}日 ${two(it.hour)}:${two(it.minute)}"
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')
}

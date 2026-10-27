package com.abc.daodian.intake.core.inbox

import com.abc.daodian.intake.core.inbox.InboxState.DONE
import com.abc.daodian.intake.core.inbox.InboxState.FAILED
import com.abc.daodian.intake.core.inbox.InboxState.PENDING
import com.abc.daodian.intake.core.inbox.InboxState.WAITING

/**
 * 收件箱里和来的这条有关的那一行：同指纹的；没有的话，同一条通知（包名、key、postTime 一样）还在等真正文的遮蔽版。
 * [sameText] 是前一种
 */
data class Existing(val id: Long, val state: InboxState, val rules: Set<Long>, val sameText: Boolean)

/**
 * 一条通知到了收件箱门口，怎么落（DESIGN.md §12.3）。只做判断，Room 仓库照着写库 ——
 * 去重、补记新规则、遮蔽版改判这几样不起库就能测
 */
sealed interface Landing {

    /** 新的一行 */
    data class Insert(val state: InboxState, val rules: Set<Long>) : Landing

    /**
     * 已经在的那条：补记 [rules]（只有新中的），状态改成 [state]。
     * 从办完、没办成转回待办的，次数清零（仓库办）
     */
    data class AddRules(val id: Long, val rules: Set<Long>, val state: InboxState) : Landing

    /** 等着的遮蔽版，真正文到了：内容换成这一条，规则按这次判的 [rules] 重记，转待办 */
    data class Replace(val id: Long, val rules: Set<Long>) : Landing

    /** 等着的遮蔽版，真正文到了却一条规则都对不上：删掉那一行 */
    data class Delete(val id: Long) : Landing

    /** 重复的、没有新东西 */
    data object Skip : Landing

    companion object {

        /**
         * @param matched 这次中了的规则（遮蔽版判不了正文的也算中）；可以是空的 —— 真正文对不上，要删等着的遮蔽版
         * @param lastLook 监听层最后一次重读（2 分钟那次）：还是遮蔽版就不等了，交管家照「看不清」办
         */
        fun of(redacted: Boolean, lastLook: Boolean, matched: Set<Long>, existing: Existing?): Landing {
            val hold = redacted && !lastLook
            if (existing == null) {
                return if (matched.isEmpty()) Skip else Insert(if (hold) WAITING else PENDING, matched)
            }
            if (!existing.sameText) {
                // 遮蔽版在等（仓库只拿等着的来比）。来的也是遮蔽版就算重复
                if (redacted || existing.state != WAITING) return Skip
                return if (matched.isEmpty()) Delete(existing.id) else Replace(existing.id, matched)
            }
            val added = matched - existing.rules
            val state = when {
                existing.state == WAITING && !hold -> PENDING
                added.isEmpty() -> return Skip
                existing.state == DONE || existing.state == FAILED -> PENDING
                // 等着的接着等；待办、办着的照旧，办着的那轮落库时发现有新规则没说法，会转回待办
                else -> existing.state
            }
            return AddRules(existing.id, added, state)
        }
    }
}

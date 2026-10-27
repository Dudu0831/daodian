package com.abc.daodian.intake.core.steward

import com.abc.daodian.intake.core.inbox.InboxNotice
import com.abc.daodian.intake.core.inbox.InboxState

/**
 * 管家的一个决定（Command）：工具只交上来，不改库；一轮跑完由 [DecisionBuffer] 算好，一个事务落库（DESIGN.md §12.4）。
 * [rules]：这个决定对的是这条通知中的哪几条规则，空的 = 还没说法的全部
 */
sealed interface Decision {
    val noticeId: Long
    val rules: Set<Long>

    /** 交给 [to] 这个去处（模块的 `NoticeTarget.id`），[note] 是写给子代理的附言，可以空着 */
    data class HandOff(override val noticeId: Long, override val rules: Set<Long>, val to: String, val note: String) : Decision

    /** 不相关，[why] 写在记录页上给人看 */
    data class Ignore(override val noticeId: Long, override val rules: Set<Long>, val why: String) : Decision
}

/** 一轮落库的全部（一个事务）：管家的决定 + 这一批每条接下来的状态和次数 */
data class Settlement(val decisions: List<Decision>, val notices: List<Settled>) {

    data class Settled(val id: Long, val state: InboxState, val attempts: Int)

    companion object {
        /** 整批跑失败（断网、网关报错）：决定都不要，全回待办，次数不加 —— 断网时不该把一条条通知耗成「没办成」 */
        fun failed(batch: List<InboxNotice>) =
            Settlement(emptyList(), batch.map { Settled(it.id, InboxState.PENDING, it.attempts) })
    }
}

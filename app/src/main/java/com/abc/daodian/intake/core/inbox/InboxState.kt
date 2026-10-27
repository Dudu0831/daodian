package com.abc.daodian.intake.core.inbox

/**
 * 收件箱一行的状态（DESIGN.md §12.3）。转移写死在 [next]，乱跳就抛。库里存的是名字，不改名
 *
 * ```
 * WAITING ─ 真正文到了 / 最后一次重读还是遮蔽 / 等太久 ─▶ PENDING
 * PENDING ─ 管家领走 ─▶ WORKING
 * WORKING ─ 每条规则都有说法 ─▶ DONE
 *         ─ 没覆盖全（次数 +1）、整批跑失败、上回领走进程没了 ─▶ PENDING
 *         ─ 第 3 次还没覆盖全 ─▶ FAILED
 * DONE、FAILED ─ 又中了新规则 / 记录页点重试 ─▶ PENDING
 * ```
 */
enum class InboxState {
    /** 遮蔽版，等真正文：不交管家 */
    WAITING,
    PENDING,
    WORKING,
    DONE,
    FAILED;

    val next: Set<InboxState>
        get() = when (this) {
            WAITING -> setOf(PENDING)
            PENDING -> setOf(WORKING)
            WORKING -> setOf(DONE, PENDING, FAILED)
            DONE, FAILED -> setOf(PENDING)
        }

    /** 转到 [to]，不许的就抛。原地不动也算对 */
    fun to(to: InboxState): InboxState {
        check(to == this || to in next) { "收件箱：$this 不能转到 $to" }
        return to
    }
}

/**
 * 交接（`handoffs`，Outbox）一行的状态：管家定了交给谁就是待交，`Dispatcher` 交出去了是交了，
 * 交 3 次都抛错是交不出。库里存的是名字，不改名
 */
enum class HandoffState { DUE, DELIVERED, FAILED }

/** 收件箱的几个数，用户定的和跟着定的都在这里 */
object InboxPolicy {

    /** 办完的留几天（用户 10-26 定的）。待办、没办成的不清 */
    const val KEEP_DAYS = 30

    /** 一条通知最多给管家办几次，还没覆盖全就标没办成 */
    const val MAX_ATTEMPTS = 3

    /** 遮蔽版最多等多久：重读是内存里排的，进程没了就没人叫它，领待办时把等太久的放出来 */
    const val WAIT_MAX_MILLIS = 10 * 60_000L
}

package com.abc.daodian.intake.core.inbox

import com.abc.daodian.intake.Notice

/**
 * 管家领走的一条（`InboxRepository.claim`），标题、正文是整理过的。
 * [rules]：中了、还没有说法的规则 —— 管家只照这几条办，办过的不再给它看
 */
data class InboxNotice(
    val id: Long,
    val pkg: String,
    val at: Long,
    val title: String?,
    val body: String?,
    val extra: String?,
    val redacted: Boolean,
    val rules: Set<Long>,
    val attempts: Int
)

/** 落进收件箱的那一行。[fresh]：这一次新插的 */
data class Stored(val id: Long, val state: InboxState, val fresh: Boolean)

/** 同一条投好几次，按它去重：`key | postTime | 正文`，和记账的 `raw_notification` 一样 */
val Notice.fingerprint: String get() = "$key|$postTime|${text.orEmpty()}"

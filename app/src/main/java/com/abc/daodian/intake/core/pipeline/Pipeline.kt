package com.abc.daodian.intake.core.pipeline

import com.abc.daodian.intake.Notice
import com.abc.daodian.intake.core.format.Formats
import com.abc.daodian.intake.core.format.NoticeFormat
import com.abc.daodian.intake.core.format.Normalized
import com.abc.daodian.intake.core.inbox.Stored
import com.abc.daodian.intake.core.ports.InboxRepository
import com.abc.daodian.intake.core.ports.RuleRepository

/**
 * 流水线上的一条：走到哪一段、带着什么
 *
 * @param lastLook 监听层最后一次重读（2 分钟那次）：还是遮蔽版就不等了
 * @param matched 中了的规则；遮蔽版判不了正文的也先算中
 * @param stored 落进收件箱的那一行
 */
data class Catch(
    val n: Normalized,
    val lastLook: Boolean = false,
    val matched: Set<Long> = emptySet(),
    val stored: Stored? = null
)

/** 一段。返回 null = 到此为止（没有规则听这个 app、没中、重复） */
fun interface Stage {
    suspend fun handle(c: Catch): Catch?
}

/**
 * 收（DESIGN.md §2.3「收」→ 硬条件 → 收件箱），代码，不允许失败。每一段能单独换、单独测；
 * 以后要加一道（比如全局隐私闸，现在不做，§12.9）就在 [Match] 前面插一段，前后都不用动。
 * 跑完四段才返回：扫通知栏要等每一条都过完
 */
class Pipeline(private val stages: List<Stage>) {

    suspend fun run(notice: Notice, lastLook: Boolean = false): Catch? {
        var c = Catch(Normalized.of(notice), lastLook)
        for (stage in stages) c = stage.handle(c) ?: return null
        return c
    }

    companion object {
        /** 标准的四段：整理格式 → 对硬条件 → 落收件箱 → 叫重读、叫管家 */
        fun standard(
            rules: RuleRepository,
            inbox: InboxRepository,
            reread: (Notice) -> Unit,
            pending: () -> Unit,
            now: () -> Long = System::currentTimeMillis,
            formats: (String) -> NoticeFormat = Formats::of
        ) = Pipeline(listOf(Normalize(formats), Match(rules), Store(inbox, now), Wake(reread, pending)))
    }
}

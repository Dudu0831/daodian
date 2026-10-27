package com.abc.daodian.intake.core.pipeline

import com.abc.daodian.intake.Notice
import com.abc.daodian.intake.core.format.Formats
import com.abc.daodian.intake.core.format.NoticeFormat
import com.abc.daodian.intake.core.inbox.InboxState
import com.abc.daodian.intake.core.ports.InboxRepository
import com.abc.daodian.intake.core.ports.RuleRepository
import com.abc.daodian.intake.core.rule.Verdict

/** 按包名挑整理格式，剥掉「[3条]」之类（[Formats]） */
class Normalize(private val formats: (String) -> NoticeFormat = Formats::of) : Stage {
    override suspend fun handle(c: Catch) = c.copy(n = formats(c.n.raw.pkg).normalize(c.n.raw))
}

/**
 * 听这个 app 的规则，每条对一遍硬条件。没有规则听这个 app 的到此为止 —— 绝大多数通知停在这里，不碰库。
 * 有规则听、却一条都没中的照样往下走：可能有它的遮蔽版在收件箱里等着改判
 */
class Match(private val rules: RuleRepository) : Stage {
    override suspend fun handle(c: Catch): Catch? {
        val mine = rules.enabled().filter { it.watches(c.n.raw.pkg) }
        if (mine.isEmpty()) return null
        return c.copy(matched = mine.filter { it.hard.test(c.n) != Verdict.NO }.map { it.id }.toSet())
    }
}

/** 落进收件箱（照 `Landing`，DESIGN.md §12.3）。没落下东西的到此为止 */
class Store(private val inbox: InboxRepository, private val now: () -> Long) : Stage {
    override suspend fun handle(c: Catch): Catch? {
        // 遮蔽版一条都没中：库里没有要它改判的，不碰库
        if (c.matched.isEmpty() && c.n.raw.redacted) return null
        val stored = inbox.receive(c.n, c.matched, c.lastLook, now()) ?: return null
        return c.copy(stored = stored)
    }
}

/** 新存下的遮蔽版：叫监听层过一会儿重读（5 秒、30 秒、2 分钟，DESIGN.md §2.3） */
class Wake(private val reread: (Notice) -> Unit) : Stage {
    override suspend fun handle(c: Catch): Catch {
        val s = c.stored
        if (s != null && s.fresh && s.state == InboxState.WAITING) reread(c.n.raw)
        return c
    }
}

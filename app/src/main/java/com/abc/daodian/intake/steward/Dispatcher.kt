package com.abc.daodian.intake.steward

import com.abc.daodian.intake.core.ports.Due
import com.abc.daodian.intake.core.ports.HandoffRepository
import com.abc.daodian.intake.core.rule.AppNames
import com.abc.daodian.intake.plugin.Handed
import com.abc.daodian.intake.plugin.Receipt
import kotlinx.coroutines.CancellationException

/** 一个去处收活的那一下：装的时候拿 `NoticeTarget.accept` 绑上 Context 交进来 */
typealias Accept = suspend (List<Handed>) -> List<Receipt>

/**
 * 交接（Transactional Outbox 的投递端，DESIGN.md §12.5）：把待交的按去处分好交出去，回执写回去。
 * 模块抛错、没回执的算这次没交成，次数 +1，第 3 次标交不出；进程被杀也不丢，待交的还在库里，下次接着交。
 * 管家不等它：管家落完库就算办完了
 *
 * @param targets 去处 id → 收活的那一下
 */
class Dispatcher(
    private val repo: HandoffRepository,
    private val targets: Map<String, Accept>,
    private val names: AppNames,
    private val now: () -> Long = System::currentTimeMillis
) {

    /** 交一轮，返回交成了几次 */
    suspend fun runOnce(): Int {
        var delivered = 0
        for ((target, list) in repo.due(LIMIT).groupBy { it.target }) {
            val accept = targets[target]
            if (accept == null) {
                list.forEach { repo.missed(it.id, "没有「$target」这个去处", now()) }
                continue
            }
            val receipts = try {
                accept(list.map(::handedOf)).associateBy { it.handoffId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                list.forEach { repo.missed(it.id, e.message ?: e.javaClass.simpleName, now()) }
                continue
            }
            for (d in list) {
                val r = receipts[d.id]
                if (r != null) {
                    repo.delivered(d.id, r.text, r.route, now())
                    delivered++
                } else {
                    repo.missed(d.id, NO_RECEIPT, now())
                }
            }
        }
        return delivered
    }

    private fun handedOf(d: Due) = Handed(d.id, d.noticeId, d.notice, names.of(d.notice.pkg), d.title, d.body, d.note)

    companion object {
        /** 一轮最多交几次 */
        const val LIMIT = 50

        const val NO_RECEIPT = "没办成（模型没配、断网、跑失败）"
    }
}

package com.abc.daodian.intake.core.steward

import com.abc.daodian.intake.core.inbox.InboxNotice
import com.abc.daodian.intake.core.inbox.InboxPolicy
import com.abc.daodian.intake.core.inbox.InboxState

/**
 * 管家这一轮的决定先收在这里（Command 的收集端）：`hand_off`、`ignore` 两个工具往里放，不对的当场退回，
 * 一轮跑完 [settle] 算出要落库的。跑到一半断了就整个扔掉，库里什么都没写，下次整批重来。
 *
 * 每条通知中的每条规则都要有个说法（DESIGN.md §12.4）：交给一个或几个去处，或者不相关，不能又交又说不相关。
 *
 * @param batch 这一批（`InboxRepository.claim` 领来的）
 * @param destinations 能交的去处的 id
 */
class DecisionBuffer(private val batch: List<InboxNotice>, private val destinations: List<String>) {

    private val notices = batch.associateBy { it.id }

    /** (通知, 规则) → 交给了哪几个去处 */
    private val handed = LinkedHashMap<Pair<Long, Long>, LinkedHashSet<String>>()

    /** (通知, 去处) → 附言，后交的盖前面的 */
    private val notes = LinkedHashMap<Pair<Long, String>, String>()

    /** (通知, 规则) → 为什么不相关 */
    private val ignored = LinkedHashMap<Pair<Long, Long>, String>()

    /** 收一个决定。收下了是 null；不对的退回一句给模型看，写清哪里不对 */
    @Synchronized
    fun offer(d: Decision): String? {
        val n = notices[d.noticeId] ?: return "n${d.noticeId} 不在这一批（这一批是 ${batch.joinToString("、") { "n${it.id}" }}）"
        val stray = d.rules - n.rules
        if (stray.isNotEmpty()) return "${stray.r()} 不是 n${n.id} 要你定的规则（它的是 ${n.rules.r()}）"
        return when (d) {
            is Decision.HandOff -> handOff(n, d)
            is Decision.Ignore -> ignore(n, d)
        }
    }

    private fun handOff(n: InboxNotice, d: Decision.HandOff): String? {
        if (d.to !in destinations) return "没有「${d.to}」这个去处，能交的是 ${destinations.joinToString("、")}"
        val rules = d.rules.ifEmpty { n.rules.filter { (n.id to it) !in ignored }.toSet() }
        if (rules.isEmpty()) return "n${n.id} 的规则都标了不相关，没有可交的"
        val clash = rules.filter { (n.id to it) in ignored }.toSet()
        if (clash.isNotEmpty()) return "n${n.id} 的 ${clash.r()} 已经标了不相关，不能再交出去"
        rules.forEach { handed.getOrPut(n.id to it) { LinkedHashSet() } += d.to }
        notes[n.id to d.to] = d.note.trim()
        return null
    }

    private fun ignore(n: InboxNotice, d: Decision.Ignore): String? {
        if (d.why.isBlank()) return "why 是空的：写一句为什么不相关"
        val rules = d.rules.ifEmpty { n.rules.filter { (n.id to it) !in handed }.toSet() }
        if (rules.isEmpty()) return "n${n.id} 的规则都交出去了，不用再标"
        val clash = rules.filter { (n.id to it) in handed }.toSet()
        if (clash.isNotEmpty()) return "n${n.id} 的 ${clash.r()} 已经交出去了，不能再标不相关"
        rules.forEach { ignored[n.id to it] = d.why.trim() }
        return null
    }

    /** 还没说法的：通知 → 规则。工具每办一次把它念给模型，免得漏 */
    @Synchronized
    fun remaining(): Map<Long, Set<Long>> =
        batch.associate { n -> n.id to n.rules.filter { !decided(n.id, it) }.toSet() }.filterValues { it.isNotEmpty() }

    /**
     * 一轮跑完：要落库的决定，和每条接下来的状态 —— 都有说法的办完；没覆盖全的回待办、次数 +1，
     * 第 [InboxPolicy.MAX_ATTEMPTS] 次还没覆盖全的标没办成。已经有说法的那几条照样落库，下次只给它看剩下的
     */
    @Synchronized
    fun settle(): Settlement {
        val decisions = buildList<Decision> {
            notes.forEach { (key, note) ->
                val (id, to) = key
                val rules = handed.filter { (k, tos) -> k.first == id && to in tos }.keys.map { it.second }.toSet()
                add(Decision.HandOff(id, rules, to, note))
            }
            ignored.entries.groupBy({ it.key.first to it.value }, { it.key.second }).forEach { (key, rules) ->
                add(Decision.Ignore(key.first, rules.toSet(), key.second))
            }
        }
        val settled = batch.map { n ->
            val left = n.rules.any { !decided(n.id, it) }
            val tries = n.attempts + 1
            when {
                !left -> Settlement.Settled(n.id, InboxState.DONE, n.attempts)
                tries >= InboxPolicy.MAX_ATTEMPTS -> Settlement.Settled(n.id, InboxState.FAILED, tries)
                else -> Settlement.Settled(n.id, InboxState.PENDING, tries)
            }
        }
        return Settlement(decisions, settled)
    }

    private fun decided(id: Long, rule: Long) = (id to rule) in handed || (id to rule) in ignored

    private fun Set<Long>.r() = sorted().joinToString("、") { "r$it" }
}

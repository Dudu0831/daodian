package com.abc.daodian.intake.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.intake.NoticeHub
import com.abc.daodian.intake.core.inbox.HandoffState
import com.abc.daodian.intake.core.inbox.InboxState
import com.abc.daodian.intake.core.rule.AppNames
import com.abc.daodian.intake.core.rule.Rule
import com.abc.daodian.intake.data.HandoffRow
import com.abc.daodian.intake.data.IntakeDatabase
import com.abc.daodian.intake.data.IntakeRepos
import com.abc.daodian.intake.data.NoticeRow
import com.abc.daodian.intake.data.NoticeRuleRow
import com.abc.daodian.intake.data.RuleOutcome
import com.abc.daodian.shared.apps.AppCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 一行结果底下那句的颜色：交出去了 / 不相关 / 还在办 / 没办成 */
enum class Tone { DONE, QUIET, WAIT, BAD }

/**
 * 一条通知变成了什么，一句一行：「→ 提醒 · 18:00 取快递」「不相关 · 广告」「还没办」「→ 记账 · 没办成」。
 * [route] 点了去哪（那条提醒）；[retryNotice]、[retryHandoff] 有值就带「再试」
 */
data class Outcome(
    val text: String,
    val tone: Tone,
    val route: String? = null,
    val retryNotice: Long? = null,
    val retryHandoff: Long? = null
)

/** 收件箱的一条，连同它中了的规则、交接和那几句结果 */
data class Entry(
    val row: NoticeRow,
    val app: String,
    val rules: List<NoticeRuleRow>,
    val handoffs: List<HandoffRow>,
    val outcomes: List<Outcome>
)

/** 规则页的一行：规则、硬条件的人话、进来过几条 */
data class RuleItem(val rule: Rule, val describe: String, val count: Int)

/**
 * 规则页、规则详情、收到的通知、一条通知四页共用（DESIGN.md §12.2、§12.3）。按 Activity 取：
 * 在详情页删了规则退回列表，撤销条还在。全读 `intake.db` 的 Flow，管家办完一条页面当场变
 */
class InboxViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = IntakeDatabase.get(app).dao()
    private val rules = IntakeRepos.rules(app)
    val names = AppNames { AppCatalog.label(app, it) }

    /** 收件箱，新的在前。null = 还没读出来 */
    val entries: StateFlow<List<Entry>?> =
        combine(dao.observeNotices(), dao.observeNoticeRules(), dao.observeHandoffs()) { notices, nrs, hs ->
            val byNotice = nrs.groupBy { it.noticeId }
            val handed = hs.groupBy { it.noticeId }
            notices.map { n ->
                val r = byNotice[n.id].orEmpty()
                val h = handed[n.id].orEmpty()
                Entry(n, names.of(n.pkg), r, h, outcomesOf(n, r, h))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 全部规则，在听的在前。null = 还没读出来 */
    val ruleItems: StateFlow<List<RuleItem>?> =
        combine(rules.all(), dao.observeNoticeRules()) { list, nrs ->
            val counts = nrs.groupingBy { it.ruleId }.eachCount()
            list.sortedWith(compareByDescending<Rule> { it.enabled }.thenBy { it.id })
                .map { RuleItem(it, it.hard.describe(names), counts[it.id] ?: 0) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 规则编号 → 名字，一条通知那页写「中了哪条」用（删了的规则不在里面） */
    val ruleNames: StateFlow<Map<Long, String>> =
        rules.all().map { list -> list.associate { it.id to it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setEnabled(rule: Rule, enabled: Boolean) = viewModelScope.launch {
        rules.setEnabled(rule.id, enabled, System.currentTimeMillis())
    }

    private val _deleted = MutableStateFlow<Rule?>(null)

    /** 刚删的那条，5 秒内能撤销（删都不弹确认，DESIGN.md §08） */
    val deleted: StateFlow<Rule?> = _deleted.asStateFlow()

    fun delete(rule: Rule) = viewModelScope.launch {
        rules.delete(rule.id)
        _deleted.value = rule
        delay(UNDO_MILLIS)
        if (_deleted.value === rule) _deleted.value = null
    }

    fun undoDelete() {
        val rule = _deleted.value ?: return
        _deleted.value = null
        viewModelScope.launch { rules.restore(rule) }
    }

    fun retry(o: Outcome) = viewModelScope.launch {
        o.retryNotice?.let { dao.retryNotice(it) }
        o.retryHandoff?.let { dao.retryHandoff(it) }
        NoticeHub.poke(getApplication())
    }

    private fun outcomesOf(n: NoticeRow, rules: List<NoticeRuleRow>, handoffs: List<HandoffRow>): List<Outcome> = when (n.state) {
        InboxState.WAITING -> listOf(Outcome("等真正文", Tone.WAIT))
        InboxState.PENDING, InboxState.WORKING -> listOf(Outcome("还没办", Tone.WAIT))
        InboxState.FAILED -> listOf(Outcome("没办成", Tone.BAD, retryNotice = n.id))
        InboxState.DONE -> handoffs.map(::handoffOutcome).ifEmpty {
            val why = rules.filter { it.outcome == RuleOutcome.IGNORED }.mapNotNull { it.why }.distinct()
            listOf(Outcome(listOf("不相关").plus(why).joinToString(" · "), Tone.QUIET))
        }
    }

    private companion object {
        const val UNDO_MILLIS = 5_000L
    }
}

/** 交出去的一次变成了什么：「→ 提醒 · 18:00 取快递」「→ 记账 · 没办成」 */
fun handoffOutcome(h: HandoffRow): Outcome {
    val to = "→ " + NoticeHub.label(h.target)
    return when (h.state) {
        HandoffState.DELIVERED -> Outcome("$to · ${h.receipt ?: "交了"}", Tone.DONE, route = h.route)
        HandoffState.DUE -> Outcome("$to · 在交", Tone.WAIT)
        HandoffState.FAILED -> Outcome("$to · 没办成", Tone.BAD, retryHandoff = h.id)
    }
}

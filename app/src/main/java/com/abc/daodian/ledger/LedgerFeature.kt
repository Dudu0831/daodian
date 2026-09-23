package com.abc.daodian.ledger

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.feature.Feature
import com.abc.daodian.agent.feature.ToolTrace
import com.abc.daodian.agent.feature.TraceView
import com.abc.daodian.agent.feature.Trigger
import com.abc.daodian.agent.shell.AppNav
import com.abc.daodian.agent.shell.FeatureUi
import com.abc.daodian.ledger.capture.PaySources
import com.abc.daodian.ledger.data.LedgerStore
import com.abc.daodian.ledger.domain.LedgerDays
import com.abc.daodian.ledger.organize.OrganizeWorker
import com.abc.daodian.ledger.presentation.LedgerCategoryScreen
import com.abc.daodian.ledger.presentation.LedgerDrawerCard
import com.abc.daodian.ledger.presentation.LedgerFormat
import com.abc.daodian.ledger.presentation.LedgerOverviewScreen
import com.abc.daodian.ledger.presentation.LedgerSettingsSection
import com.abc.daodian.ledger.presentation.LedgerTxnScreen
import com.abc.daodian.ledger.presentation.LedgerViewModel
import com.abc.daodian.ledger.presentation.Period
import com.abc.daodian.ledger.presentation.PeriodMode
import com.abc.daodian.ledger.reconciliation.LedgerCheck
import com.abc.daodian.ledger.reconciliation.LedgerCheckPrompt
import com.abc.daodian.ledger.tools.LedgerPrompt
import com.abc.daodian.ledger.tools.LedgerTools
import com.abc.daodian.ledger.tools.LedgerTrace
import com.abc.daodian.shared.ui.activityViewModel
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 记账接到 agent 上的接头。记账自己在后台转：听通知（capture）、定期整理（organize）、每晚对账（reconciliation）；
 * 这里把它交给对话 agent 和界面壳：查 / 记 / 改 / 加类别四个工具和那段提示词、痕、每晚对账那一轮、三层页面、抽屉卡、设置组。
 */
object LedgerFeature : Feature, FeatureUi {

    override val id = "ledger"

    override val prompt = LedgerPrompt.CHAT

    override fun tools(context: Context): List<Tool> = LedgerTools.forChat(LedgerStore.get(context.applicationContext))

    override fun trace(call: ToolTrace): TraceView? = LedgerTrace.of(call)

    /**
     * 每晚对账（通知上点「现在」、记账页点「现在就说」）：把没认出来的几笔列给模型，由它在对话里一笔笔问你。
     * 见 LEDGER_PLAN.md §4 ③
     */
    override suspend fun trigger(context: Context, key: String): Trigger? {
        if (key != LedgerRoutes.CHECK.substringAfter(':')) return null
        val app = context.applicationContext
        LedgerCheck.done(app)
        return LedgerCheckPrompt.build(LedgerStore.get(app))?.let { Trigger.Turn(it) }
            ?: Trigger.Note("账都对上了，没有要问你的。")
    }

    override fun onAppStart(context: Context) {
        val app = context.applicationContext
        PaySources.rebind(app)
        // 排上定期整理、排上每晚对账
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { OrganizeWorker.schedule(app) }
            runCatching { LedgerCheck.arm(app) }
        }
    }

    // ---------------- 界面：总览 → 类别 → 一笔。只看不改，改账回对话 ----------------

    override fun NavGraphBuilder.routes(nav: AppNav) {
        composable(LedgerRoutes.HOME) {
            val vm = activityViewModel<LedgerViewModel>()
            val checkTime by vm.checkTime.collectAsState()
            LedgerOverviewScreen(
                vm = vm,
                checkTime = LedgerFormat.nextCheck(checkTime),
                onBack = nav::back,
                onOpenCategory = { top, income, p ->
                    nav.open(LedgerRoutes.category(top, income, p.mode.name, LedgerDays.dayInt(p.anchor)))
                },
                onCheckNow = { nav.trigger(LedgerRoutes.CHECK) }
            )
        }

        composable(
            LedgerRoutes.CATEGORY,
            arguments = listOf(
                navArgument("top") { type = NavType.LongType },
                navArgument("income") { type = NavType.BoolType; defaultValue = false },
                navArgument("mode") { type = NavType.StringType; defaultValue = PeriodMode.MONTH.name },
                navArgument("day") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { entry ->
            val a = entry.arguments!!
            val day = a.getInt("day")
            val period = Period(
                PeriodMode.valueOf(a.getString("mode") ?: PeriodMode.MONTH.name),
                if (day > 0) LedgerDays.dateOf(day) else LocalDate.now()
            )
            LedgerCategoryScreen(
                vm = activityViewModel<LedgerViewModel>(),
                topId = a.getLong("top"),
                income = a.getBoolean("income"),
                period = period,
                onBack = nav::back,
                onOpenTxn = { nav.open(LedgerRoutes.txn(it)) }
            )
        }

        composable(
            LedgerRoutes.TXN,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            LedgerTxnScreen(
                vm = activityViewModel<LedgerViewModel>(),
                txnId = entry.arguments!!.getLong("id"),
                onBack = nav::back,
                onTalk = { nav.chat(it) }
            )
        }
    }

    @Composable
    override fun DrawerCard(open: (String) -> Unit) = LedgerDrawerCard(onOpen = { open(LedgerRoutes.HOME) })

    @Composable
    override fun SettingsSection(open: (String) -> Unit) = LedgerSettingsSection()
}

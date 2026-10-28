package com.abc.daodian.ledger.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.abc.daodian.ledger.LedgerDiagnostics
import com.abc.daodian.ledger.LedgerModule
import com.abc.daodian.ledger.LedgerRoutes
import com.abc.daodian.ledger.domain.LedgerDays
import com.abc.daodian.shared.ui.activityViewModel
import com.abc.daodian.shell.entry.AppNav
import com.abc.daodian.shell.entry.SettingsEntry
import com.abc.daodian.shell.entry.ShellEntry
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 记账给壳的：总览 → 类别 → 一笔三层页面、设置页，抽屉卡、设置行、诊断那一段 */
object LedgerShell : ShellEntry {

    override val id = LedgerModule.ID

    override val label = LedgerModule.LABEL

    // ---------------- 界面：总览 → 类别 → 一笔，只看不改，改账回对话 ----------------

    override fun NavGraphBuilder.routes(nav: AppNav) {
        composable(LedgerRoutes.HOME) {
            val vm = activityViewModel<LedgerViewModel>()
            val checkTime by vm.checkTime.collectAsState()
            val checkWaiting by vm.checkWaiting.collectAsState()
            LedgerOverviewScreen(
                vm = vm,
                checkNote = LedgerFormat.checkNote(checkTime, checkWaiting),
                onBack = nav::back,
                onOpenCategory = { top, income, p ->
                    nav.open(LedgerRoutes.category(top, income, p.mode.name, LedgerDays.dayInt(p.anchor)))
                },
                onCheckNow = { nav.trigger(LedgerRoutes.CHECK) },
                onOpenNotices = { nav.open(NOTICES_ROUTE) }
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
                onTalk = { nav.assistant(it) }
            )
        }

        composable(LedgerRoutes.SETTINGS) {
            LedgerSettingsScreen(onBack = nav::back)
        }
    }

    @Composable
    override fun DrawerCard(open: (String) -> Unit) = LedgerDrawerCard(onOpen = { open(LedgerRoutes.HOME) })

    override val settingsEntries = listOf(SettingsEntry("记账", LedgerRoutes.SETTINGS))

    override suspend fun diagnostics(context: Context): String =
        withContext(Dispatchers.IO) { LedgerDiagnostics.build(context) }
}

/** 通知管家的「收到的通知」（`intake/IntakeRoutes.NOTICES`），记账不 import 它 */
private const val NOTICES_ROUTE = "intake/notices"

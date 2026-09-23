package com.abc.daodian.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.abc.daodian.ui.chat.ChatScreen
import com.abc.daodian.ui.edit.EditReminderScreen
import com.abc.daodian.ui.ledger.LedgerCategoryScreen
import com.abc.daodian.ui.ledger.LedgerFormat
import com.abc.daodian.ui.ledger.LedgerOverviewScreen
import com.abc.daodian.ui.ledger.LedgerTxnScreen
import com.abc.daodian.ui.ledger.LedgerViewModel
import com.abc.daodian.ui.ledger.Period
import com.abc.daodian.ui.ledger.PeriodMode
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.abc.daodian.ui.list.ReminderListScreen
import com.abc.daodian.ui.settings.FireLogScreen
import com.abc.daodian.ui.settings.ProviderScreen
import com.abc.daodian.ui.settings.SettingsScreen
import com.abc.daodian.widget.WidgetTarget

private object Routes {
    const val CHAT = "chat"
    const val LIST = "list"
    const val EDIT = "edit?id={id}"
    const val SETTINGS = "settings"
    const val PROVIDER = "provider"
    const val LOG = "log"
    const val LEDGER = "ledger"
    const val LEDGER_CATEGORY = "ledger/category/{top}?income={income}&mode={mode}&day={day}"
    const val LEDGER_TXN = "ledger/txn/{id}"
    fun edit(id: Long?) = "edit?id=${id ?: -1L}"
    fun ledgerCategory(top: Long, income: Boolean, p: Period) =
        "ledger/category/$top?income=$income&mode=${p.mode.name}&day=${Period.dayInt(p.anchor)}"
    fun ledgerTxn(id: Long) = "ledger/txn/$id"
}

/**
 * 单个共享 VM，在 NavHost 外部拿一次往下传 —— 不让每个目的地各自 viewModel()，
 * 避免 Navigation-Compose 按 backstack entry 分别建实例，导致数据不同步。
 */
@Composable
fun DaodianNavHost(
    vm: MainViewModel,
    ledger: LedgerViewModel,
    navController: NavHostController = rememberNavController(),
    widgetTarget: WidgetTarget? = null,
    onWidgetTargetHandled: () -> Unit = {}
) {
    // 桌面小组件只说去处，路由字符串是 NavHost 自己的事，见 WidgetLaunch
    LaunchedEffect(widgetTarget) {
        when (widgetTarget) {
            null -> return@LaunchedEffect
            WidgetTarget.Chat -> navController.navigate(Routes.CHAT) {
                popUpTo(Routes.CHAT) { inclusive = true }
                launchSingleTop = true
            }
            WidgetTarget.List -> navController.navigate(Routes.LIST) { launchSingleTop = true }
            WidgetTarget.New -> navController.navigate(Routes.edit(null)) { launchSingleTop = true }
            is WidgetTarget.Edit ->
                navController.navigate(Routes.edit(widgetTarget.reminderId)) { launchSingleTop = true }
            // 每晚对账通知上点「现在」：回对话页，开一轮对账
            WidgetTarget.LedgerCheck -> {
                navController.navigate(Routes.CHAT) {
                    popUpTo(Routes.CHAT) { inclusive = true }
                    launchSingleTop = true
                }
                vm.startLedgerCheck()
            }
            // 桌面速记交过来的一句话：回对话页，照常发出去（正忙就先放进输入框）
            is WidgetTarget.Say -> {
                navController.navigate(Routes.CHAT) {
                    popUpTo(Routes.CHAT) { inclusive = true }
                    launchSingleTop = true
                }
                if (vm.aiBusy) vm.prefill(widgetTarget.text) else vm.sendMessage(widgetTarget.text)
            }
        }
        onWidgetTargetHandled()
    }

    NavHost(navController = navController, startDestination = Routes.CHAT) {

        composable(Routes.CHAT) {
            ChatScreen(
                vm = vm,
                ledger = ledger,
                onOpenList = { navController.navigate(Routes.LIST) },
                onOpenLedger = { navController.navigate(Routes.LEDGER) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenProvider = { navController.navigate(Routes.PROVIDER) },
                onManualAdd = { navController.navigate(Routes.edit(null)) },
                onEditReminder = { id -> navController.navigate(Routes.edit(id)) },
                onOpenTxn = { id -> navController.navigate(Routes.ledgerTxn(id)) }
            )
        }

        composable(Routes.LIST) {
            ReminderListScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Routes.edit(null)) },
                onEdit = { id -> navController.navigate(Routes.edit(id)) }
            )
        }

        composable(
            Routes.EDIT,
            arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L })
        ) { entry ->
            val id = entry.arguments?.getLong("id")?.takeIf { it >= 0 }
            EditReminderScreen(vm = vm, reminderId = id, onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenLog = { navController.navigate(Routes.LOG) },
                onOpenProvider = { navController.navigate(Routes.PROVIDER) },
                ledger = ledger
            )
        }

        composable(Routes.PROVIDER) {
            ProviderScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.LOG) {
            FireLogScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        // ---- 记账三层：总览 → 类别 → 一笔。只看不改，改账回对话 ----

        /** 回到对话页；[text] 不为空就先填进输入框 */
        fun backToChat(text: String? = null) {
            text?.let(vm::prefill)
            navController.navigate(Routes.CHAT) {
                popUpTo(Routes.CHAT) { inclusive = false }
                launchSingleTop = true
            }
        }

        composable(Routes.LEDGER) {
            val checkTime by ledger.checkTime.collectAsState()
            LedgerOverviewScreen(
                vm = ledger,
                checkTime = LedgerFormat.nextCheck(checkTime),
                onBack = { navController.popBackStack() },
                onOpenCategory = { top, income, p -> navController.navigate(Routes.ledgerCategory(top, income, p)) },
                onCheckNow = {
                    backToChat()
                    vm.startLedgerCheck()
                }
            )
        }

        composable(
            Routes.LEDGER_CATEGORY,
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
                if (day > 0) Period.dateOf(day) else java.time.LocalDate.now()
            )
            LedgerCategoryScreen(
                vm = ledger,
                topId = a.getLong("top"),
                income = a.getBoolean("income"),
                period = period,
                onBack = { navController.popBackStack() },
                onOpenTxn = { navController.navigate(Routes.ledgerTxn(it)) }
            )
        }

        composable(
            Routes.LEDGER_TXN,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            LedgerTxnScreen(
                vm = ledger,
                txnId = entry.arguments!!.getLong("id"),
                onBack = { navController.popBackStack() },
                onTalk = { backToChat(it) }
            )
        }
    }
}

package com.abc.daodian.reminder.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.abc.daodian.reminder.ReminderDiagnostics
import com.abc.daodian.reminder.ReminderModule
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.reminder.delivery.HealthCheck
import com.abc.daodian.reminder.presentation.edit.EditReminderScreen
import com.abc.daodian.reminder.presentation.list.ReminderListScreen
import com.abc.daodian.reminder.presentation.log.FireLogScreen
import com.abc.daodian.shared.ui.activityViewModel
import com.abc.daodian.shell.entry.AppNav
import com.abc.daodian.shell.entry.HealthItem
import com.abc.daodian.shell.entry.SettingsEntry
import com.abc.daodian.shell.entry.ShellEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 提醒给壳的：列表、编辑、投递日志、设置（派活两个旧路由留壳），抽屉卡、设置行、体检项、诊断那一段 */
object ReminderShell : ShellEntry {

    override val id = ReminderModule.ID

    override val label = ReminderModule.LABEL

    override fun NavGraphBuilder.routes(nav: AppNav) {
        composable(ReminderRoutes.LIST) {
            ReminderListScreen(
                vm = activityViewModel<ReminderViewModel>(),
                onBack = nav::back,
                onAdd = { nav.open(ReminderRoutes.edit(null)) },
                onEdit = { id -> nav.open(ReminderRoutes.edit(id)) }
            )
        }
        composable(
            ReminderRoutes.EDIT,
            arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L })
        ) { entry ->
            val id = entry.arguments?.getLong("id")?.takeIf { it >= 0 }
            EditReminderScreen(vm = activityViewModel<ReminderViewModel>(), reminderId = id, onBack = nav::back)
        }
        composable(ReminderRoutes.LOG) {
            FireLogScreen(vm = activityViewModel<ReminderViewModel>(), onBack = nav::back)
        }
        composable(ReminderRoutes.SETTINGS) {
            ReminderSettingsScreen(onBack = nav::back, open = nav::open)
        }
        // 派活的两个旧路由留壳：通知栏里旧通知点进来，跳规则页（路由字符串，提醒不 import 通知监听层）
        composable(ReminderRoutes.RELAY, arguments = listOf(navArgument("id") { type = NavType.LongType })) { RelayShim(nav) }
        composable(ReminderRoutes.RELAY_OLD) { RelayShim(nav) }
    }

    @Composable
    override fun DrawerCard(open: (String) -> Unit) = ReminderDrawerCard(onOpen = { open(ReminderRoutes.LIST) })

    override val settingsEntries = listOf(SettingsEntry("提醒", ReminderRoutes.SETTINGS, "当天事项几点提醒、投递日志"))

    @Composable
    override fun HealthNote() = DeliveryNote()

    override fun health(context: Context): List<HealthItem> =
        HealthCheck.run(context).map { HealthItem(it.label, it.ok, it.detail, it.fixIntent) } +
            HealthItem(HealthCheck.MANUAL_LABEL, ok = null, detail = HealthCheck.MANUAL_DETAIL, fixIntent = HealthCheck.manualIntent(context))

    override suspend fun diagnostics(context: Context): String =
        withContext(Dispatchers.IO) { ReminderDiagnostics.build(context) }
}

/** 派活旧路由的壳：先退掉自己，再去规则页 */
@Composable
private fun RelayShim(nav: AppNav) {
    LaunchedEffect(Unit) {
        nav.back()
        nav.open(RULES_ROUTE)
    }
}

/** 通知监听层的规则页（`intake/IntakeRoutes.RULES`），提醒不 import 它 */
private const val RULES_ROUTE = "intake/rules"

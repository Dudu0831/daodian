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
import com.abc.daodian.ui.list.ReminderListScreen
import com.abc.daodian.ui.settings.FireLogScreen
import com.abc.daodian.ui.settings.SettingsScreen
import com.abc.daodian.widget.WidgetTarget

private object Routes {
    const val CHAT = "chat"
    const val LIST = "list"
    const val EDIT = "edit?id={id}"
    const val SETTINGS = "settings"
    const val LOG = "log"
    fun edit(id: Long?) = "edit?id=${id ?: -1L}"
}

/**
 * 单个共享 VM，在 NavHost 外部拿一次往下传 —— 不让每个目的地各自 viewModel()，
 * 避免 Navigation-Compose 按 backstack entry 分别建实例，导致数据不同步。
 */
@Composable
fun DaodianNavHost(
    vm: MainViewModel,
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
        }
        onWidgetTargetHandled()
    }

    NavHost(navController = navController, startDestination = Routes.CHAT) {

        composable(Routes.CHAT) {
            ChatScreen(
                vm = vm,
                onOpenList = { navController.navigate(Routes.LIST) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onManualAdd = { navController.navigate(Routes.edit(null)) },
                onEditReminder = { id -> navController.navigate(Routes.edit(id)) }
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
                onOpenLog = { navController.navigate(Routes.LOG) }
            )
        }

        composable(Routes.LOG) {
            FireLogScreen(vm = vm, onBack = { navController.popBackStack() })
        }
    }
}

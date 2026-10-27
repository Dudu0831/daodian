package com.abc.daodian.intake.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.abc.daodian.intake.Intake
import com.abc.daodian.intake.IntakeModule
import com.abc.daodian.intake.IntakeRoutes
import com.abc.daodian.shared.format.DiagnosticText.time
import com.abc.daodian.shell.entry.AppNav
import com.abc.daodian.shell.entry.PermissionStatus
import com.abc.daodian.shell.entry.ShellEntry
import kotlinx.coroutines.flow.first

/** 通知监听层给壳的：勾 app 的页面、「权限与监听」页里的几组、设置首页那一行上的一句、诊断那一段 */
object IntakeShell : ShellEntry {

    override val id = IntakeModule.ID

    override val label = IntakeModule.LABEL

    /** 「导出诊断」里这一段：使用权、监听连没连着、谁在听哪些 app（包名） */
    override suspend fun diagnostics(context: Context): String = buildString {
        val l = Intake.listener.value
        appendLine("使用权 ${if (Intake.granted(context)) "开着" else "没开"} · 监听${if (l.connected) "连着，从 ${time(l.since)} 起" else "没连着"}")
        Intake.routesFlow(context).first().forEach { (id, apps) ->
            appendLine("$id 听：" + apps.sorted().joinToString("、").ifEmpty { "一个没勾" })
        }
    }

    override fun NavGraphBuilder.routes(nav: AppNav) {
        // 按这一页取 ViewModel（不是按 Activity）：每次进来重新分「在听的 / 其他」两段，订阅者 id 从路由参数来
        composable(IntakeRoutes.APPS, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            AppPickerScreen(vm = viewModel(), onBack = nav::back)
        }
        composable(IntakeRoutes.RULES) { RulesScreen(onBack = nav::back, open = nav::open) }
        composable(IntakeRoutes.RULE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            RuleScreen(id = it.arguments?.getLong("id") ?: 0L, onBack = nav::back, open = nav::open)
        }
        composable(IntakeRoutes.NOTICES) { NoticesScreen(onBack = nav::back, open = nav::open) }
        composable(IntakeRoutes.NOTICE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            NoticeScreen(id = it.arguments?.getLong("id") ?: 0L, onBack = nav::back, open = nav::open)
        }
    }

    @Composable
    override fun PermissionSection(open: (String) -> Unit) = IntakePermissionSection(open)

    @Composable
    override fun permissionStatus(): PermissionStatus? = intakePermissionStatus()?.let { (text, ok) -> PermissionStatus(text, ok) }
}

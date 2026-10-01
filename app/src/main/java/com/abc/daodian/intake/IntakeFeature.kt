package com.abc.daodian.intake

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.abc.daodian.agent.diagnostics.Diagnostics
import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.feature.Feature
import com.abc.daodian.agent.shell.AppNav
import com.abc.daodian.agent.shell.FeatureUi
import com.abc.daodian.agent.shell.PermissionStatus
import com.abc.daodian.intake.presentation.AppPickerScreen
import com.abc.daodian.intake.presentation.IntakePermissionSection
import com.abc.daodian.intake.presentation.intakePermissionStatus
import kotlinx.coroutines.flow.first

/**
 * 通知监听层接到界面壳上的接头：「权限与监听」页里的几组、设置首页那一行上的一句、勾 app 的页面、
 * 冷启动催系统把监听绑回来。
 * 它不跟模型打交道 —— 没有提示词、没有工具。这一层只有这个文件和 `presentation/` 碰 agent，
 * 核心（[Intake]、[NoticeListenerService]）永远不碰。见 DESIGN.md §2.3
 */
object IntakeFeature : Feature, FeatureUi {

    override val id = "intake"

    override val label = "通知监听"

    override val prompt = ""

    override fun tools(context: Context): List<Tool> = emptyList()

    override fun onAppStart(context: Context) = Intake.rebind(context.applicationContext)

    /** 「导出诊断」里这一段：使用权、监听连没连着、谁在听哪些 app（包名） */
    override suspend fun diagnostics(context: Context): String = buildString {
        val l = Intake.listener.value
        appendLine("使用权 ${if (Intake.granted(context)) "开着" else "没开"} · 监听${if (l.connected) "连着，从 ${Diagnostics.time(l.since)} 起" else "没连着"}")
        Intake.routesFlow(context).first().forEach { (id, apps) ->
            appendLine("$id 听：" + apps.sorted().joinToString("、").ifEmpty { "一个没勾" })
        }
    }

    override fun NavGraphBuilder.routes(nav: AppNav) {
        // 按这一页取 ViewModel（不是按 Activity）：每次进来重新分「在听的 / 其他」两段，订阅者 id 从路由参数来
        composable(IntakeRoutes.APPS, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            AppPickerScreen(vm = viewModel(), onBack = nav::back)
        }
    }

    @Composable
    override fun PermissionSection(open: (String) -> Unit) = IntakePermissionSection(open)

    @Composable
    override fun permissionStatus(): PermissionStatus? = intakePermissionStatus()?.let { (text, ok) -> PermissionStatus(text, ok) }
}

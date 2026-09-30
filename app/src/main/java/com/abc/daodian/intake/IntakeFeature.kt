package com.abc.daodian.intake

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.feature.Feature
import com.abc.daodian.agent.shell.AppNav
import com.abc.daodian.agent.shell.FeatureUi
import com.abc.daodian.intake.presentation.AppPickerScreen
import com.abc.daodian.intake.presentation.IntakeSettingsSection
import com.abc.daodian.intake.presentation.IntakeStatusScreen
import com.abc.daodian.shared.ui.activityViewModel

/**
 * 通知监听层接到界面壳上的接头：设置里「通知监听」一组、两个页面、冷启动催系统把监听绑回来。
 * 它不跟模型打交道 —— 没有提示词、没有工具。这一层只有这个文件和 `presentation/` 碰 agent，
 * 核心（[Intake]、[NoticeListenerService]）永远不碰。见 DESIGN.md §2.3
 */
object IntakeFeature : Feature, FeatureUi {

    override val id = "intake"

    override val prompt = ""

    override fun tools(context: Context): List<Tool> = emptyList()

    override fun onAppStart(context: Context) = Intake.rebind(context.applicationContext)

    override fun NavGraphBuilder.routes(nav: AppNav) {
        composable(IntakeRoutes.STATUS) {
            IntakeStatusScreen(vm = activityViewModel(), onBack = nav::back, onOpen = nav::open)
        }
        // 按这一页取 ViewModel（不是按 Activity）：每次进来重新分「在听的 / 其他」两段，订阅者 id 从路由参数来
        composable(IntakeRoutes.APPS, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            AppPickerScreen(vm = viewModel(), onBack = nav::back)
        }
    }

    @Composable
    override fun SettingsSection(open: (String) -> Unit) = IntakeSettingsSection(open)
}

package com.abc.daodian.reminder

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.feature.Feature
import com.abc.daodian.agent.feature.HealthItem
import com.abc.daodian.agent.feature.ToolTrace
import com.abc.daodian.agent.feature.TraceView
import com.abc.daodian.agent.shell.AppNav
import com.abc.daodian.agent.shell.FeatureUi
import com.abc.daodian.agent.shell.SettingsEntry
import com.abc.daodian.reminder.application.Reminders
import com.abc.daodian.reminder.delivery.HealthCheck
import com.abc.daodian.reminder.delivery.Notifier
import com.abc.daodian.reminder.presentation.DeliveryNote
import com.abc.daodian.reminder.presentation.ReminderDrawerCard
import com.abc.daodian.reminder.presentation.RelayEntryStatus
import com.abc.daodian.reminder.presentation.ReminderEntryStatus
import com.abc.daodian.reminder.presentation.ReminderSettingsScreen
import com.abc.daodian.reminder.presentation.ReminderViewModel
import com.abc.daodian.reminder.presentation.edit.EditReminderScreen
import com.abc.daodian.reminder.presentation.list.ReminderListScreen
import com.abc.daodian.reminder.presentation.log.FireLogScreen
import com.abc.daodian.reminder.presentation.relay.RelayScreen
import com.abc.daodian.reminder.presentation.relay.RelayViewModel
import com.abc.daodian.reminder.scheduling.Rescheduler
import com.abc.daodian.reminder.scheduling.SweepWorker
import com.abc.daodian.reminder.tools.CreateReminderTool
import com.abc.daodian.reminder.tools.ReminderPrompt
import com.abc.daodian.reminder.tools.ReminderTrace
import com.abc.daodian.shared.ui.activityViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 提醒接到 agent 上的接头。提醒本身不靠它活着：排期、到点、重排、巡检都在 scheduling / delivery 里，
 * 断网、不经过模型（手动填一条）照样响。这里只是把它交给 agent 和界面壳：
 * `create_reminder` 工具和那段提示词、痕、例句、体检项、三个页面、抽屉卡、设置组。
 */
object ReminderFeature : Feature, FeatureUi {

    override val id = "reminder"

    override val label = "提醒"

    override val prompt = ReminderPrompt.RULES

    override fun tools(context: Context): List<Tool> {
        val app = context.applicationContext
        return listOf(CreateReminderTool { plan, ctx -> Reminders.commitPlan(app, ctx.userInput, plan, ctx.model) })
    }

    override fun trace(call: ToolTrace): TraceView? = ReminderTrace.of(call)

    override val examples = listOf(
        "三分钟后提醒我喝水。",
        "下周三下午三点，交房租。",
        "每天早上八点提醒我吃药。",
        "这周五下班前把周报发出去。"
    )

    override val manualEntry = ReminderRoutes.NEW

    override fun health(context: Context): List<HealthItem> =
        HealthCheck.run(context).map { HealthItem(it.label, it.ok, it.detail, it.fixIntent) } +
            HealthItem(HealthCheck.MANUAL_LABEL, ok = null, detail = HealthCheck.MANUAL_DETAIL, fixIntent = null)

    override fun onAppStart(context: Context) {
        val app = context.applicationContext
        Notifier.ensureChannel(app)
        SweepWorker.enqueue(app)
        // 冷启动也当作一次重排触发源 —— 被强杀后用户点开 app 就是最好的自愈时机
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { Rescheduler(app).rescheduleAll() }
                .onFailure { Log.e("Daodian/Reminder", "启动重排失败", it) }
        }
    }

    // ---------------- 界面 ----------------

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
        composable(ReminderRoutes.RELAY) {
            RelayScreen(vm = viewModel<RelayViewModel>(), onBack = nav::back, onOpen = nav::open)
        }
    }

    @Composable
    override fun DrawerCard(open: (String) -> Unit) = ReminderDrawerCard(onOpen = { open(ReminderRoutes.LIST) })

    override val settingsEntries = listOf(
        SettingsEntry("提醒", ReminderRoutes.SETTINGS) { ReminderEntryStatus() },
        SettingsEntry("派活", ReminderRoutes.RELAY, tag = "试验") { RelayEntryStatus() }
    )

    @Composable
    override fun HealthNote() = DeliveryNote()
}

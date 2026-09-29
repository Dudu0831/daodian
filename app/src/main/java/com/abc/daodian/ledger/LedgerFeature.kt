package com.abc.daodian.ledger

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.abc.daodian.agent.engine.ask.AskNote
import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.feature.Feature
import com.abc.daodian.agent.feature.PendingTrigger
import com.abc.daodian.agent.feature.ToolTrace
import com.abc.daodian.agent.feature.TraceView
import com.abc.daodian.agent.feature.Trigger
import com.abc.daodian.agent.shell.AppNav
import com.abc.daodian.agent.shell.FeatureUi
import com.abc.daodian.ledger.capture.PaySources
import com.abc.daodian.ledger.data.LedgerStore
import com.abc.daodian.ledger.domain.LedgerDays
import com.abc.daodian.ledger.domain.LedgerText
import com.abc.daodian.ledger.organize.OrganizeWorker
import com.abc.daodian.ledger.presentation.AskTagRow
import com.abc.daodian.ledger.presentation.CaptureScreen
import com.abc.daodian.ledger.presentation.CaptureViewModel
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 记账接到 agent 上的接头。记账自己在后台转：听通知（capture）、定期整理（organize）、每晚对账（reconciliation）；
 * 这里把它交给对话 agent 和界面壳：查 / 记 / 改 / 加类别四个工具和那段提示词、痕、每晚对账那一轮、三层页面、抽屉卡、设置组。
 */
object LedgerFeature : Feature, FeatureUi {

    override val id = "ledger"

    override val prompt = LedgerPrompt.CHAT

    /**
     * 实际的类别表和已有的标签，每句都垫着：整理员后来建的二级、你在账单页新建的标签，对话里也认得，
     * 不再另起一个意思差不多的。只在新建二级、新建或删掉标签时变（DESIGN.md §10.1「两个模型怎么传话」）
     */
    override suspend fun background(context: Context): String {
        val store = LedgerStore.get(context.applicationContext)
        // 按名字排，不按用得多少：挂一笔就换顺序的话，前缀缓存每句都失效
        return "记账的类别表（以这份为准）：\n" + LedgerText.categoryTree(store.categories()) +
            "\n已有的标签：" + store.tagNames().joinToString("、").ifEmpty { "还没有" }
    }

    override fun tools(context: Context): List<Tool> = LedgerTools.forChat(LedgerStore.get(context.applicationContext))

    override fun trace(call: ToolTrace): TraceView? = LedgerTrace.of(call)

    /**
     * 每晚对账（通知上点「现在」、记账页点「现在就说」）：把没认出来的几笔列给模型，由它在对话里一笔笔问你。
     * 见 DESIGN.md §10.1 ③
     */
    override suspend fun trigger(context: Context, key: String): Trigger? {
        if (key != LedgerRoutes.CHECK.substringAfter(':')) return null
        val app = context.applicationContext
        LedgerCheck.done(app)
        return LedgerCheckPrompt.build(LedgerStore.get(app))?.let { Trigger.Turn(it) }
            ?: Trigger.Note("账都对上了，没有要问你的。")
    }

    /** 通知弹了、还没对：从桌面图标进 app，对话末尾也有一段虚线等你点。见 DESIGN.md §10.1 ③ */
    override fun pendingTrigger(context: Context): Flow<PendingTrigger?> =
        LedgerCheck.waiting(context).map { w ->
            // label 要和开场白「每晚对账：……」冒号前那段一致，点了之后换成的实线上写的就是它
            w?.let { PendingTrigger(LedgerRoutes.CHECK, "每晚对账", it.at, "有 ${it.count} 笔账没认出来，现在对一下？", "现在对") }
        }

    override suspend fun dismissTrigger(context: Context, key: String) {
        if (key == LedgerRoutes.CHECK.substringAfter(':')) LedgerCheck.done(context.applicationContext)
    }

    /**
     * 对账问卡上「#12」那一笔，交卷时挂着哪些标签：收起后接在答案后面，也告诉模型已经挂好了，
     * 它就不会再打一遍（DESIGN.md §10.4「标签怎么打」）。没挂的是 null
     */
    override suspend fun askNote(context: Context, ref: String): AskNote? {
        val id = txnIdOf(ref) ?: return null
        val tags = LedgerStore.get(context.applicationContext).txns(listOf(id)).firstOrNull()?.tags.orEmpty()
        if (tags.isEmpty()) return null
        val names = tags.joinToString("、")
        return AskNote(short = names, told = "这笔挂着标签「$names」（他在问卡上点的也在里面），已经挂好了，别再打")
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

    // ---------------- 界面：总览 → 类别 → 一笔，只看不改，改账回对话；外加抓取页 ----------------

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
                onOpenCapture = { nav.open(LedgerRoutes.CAPTURE) }
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

        composable(LedgerRoutes.CAPTURE) {
            CaptureScreen(
                vm = activityViewModel<CaptureViewModel>(),
                onBack = nav::back,
                onOpenTxn = { nav.open(LedgerRoutes.txn(it)) }
            )
        }
    }

    @Composable
    override fun DrawerCard(open: (String) -> Unit) = LedgerDrawerCard(onOpen = { open(LedgerRoutes.HOME) })

    @Composable
    override fun SettingsSection(open: (String) -> Unit) = LedgerSettingsSection(onOpenCapture = { open(LedgerRoutes.CAPTURE) })

    /** 对账问卡上一笔底下的「＋ 打标签」（设计稿方向 B） */
    @Composable
    override fun AskAddon(ref: String) {
        txnIdOf(ref)?.let { AskTagRow(it) }
    }

    /** 问卡上模型写的「#12」→ 12。别的写法不认 */
    private fun txnIdOf(ref: String): Long? = Regex("""^#?(\d+)$""").find(ref.trim())?.groupValues?.get(1)?.toLongOrNull()
}

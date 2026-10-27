package com.abc.daodian.intake

import android.content.Context
import com.abc.daodian.intake.core.inbox.InboxPolicy
import com.abc.daodian.intake.core.pipeline.Pipeline
import com.abc.daodian.intake.core.rule.AppNames
import com.abc.daodian.intake.core.steward.Destination
import com.abc.daodian.intake.data.IntakeRepos
import com.abc.daodian.intake.plugin.NoticeTarget
import com.abc.daodian.intake.steward.Accept
import com.abc.daodian.intake.steward.Dispatcher
import com.abc.daodian.intake.steward.Steward
import com.abc.daodian.intake.steward.StewardScheduler
import com.abc.daodian.shared.apps.AppCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 新链路接在一起的那一份（DESIGN.md §12，[NoticeRouting.HUB] 时才用）：流水线（硬条件 → 收件箱）、
 * 管家的调度、交接。全 app 一份，头一次用时拿 Context 装好；去处清单由 `DaodianApp` 从 `Modules.kt` 交进来（intake 不 import 模块）。
 *
 * 管家一轮跑完接着交一轮：交接没交成的（模块抛错、没回执）留在库里，下次有人叫时再交
 */
object NoticeHub {

    /** 扫一遍时落进收件箱的那几条记在这个名下（`Intake.Sweep.routed`） */
    const val ID = "inbox"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var targets: List<NoticeTarget> = emptyList()

    /** `DaodianApp.onCreate` 装：各模块的 `Module.notice`，照 `Modules.kt` 的顺序。系统拉起监听之前一定已经装好 */
    fun install(list: List<NoticeTarget>) {
        targets = list
    }

    private class Wired(val pipeline: Pipeline, val scheduler: StewardScheduler)

    @Volatile
    private var wired: Wired? = null

    private fun wired(context: Context): Wired = wired ?: synchronized(this) { wired ?: build(context.applicationContext).also { wired = it } }

    private fun build(app: Context): Wired {
        val names = AppNames { AppCatalog.label(app, it) }
        val rules = IntakeRepos.rules(app)
        val inbox = IntakeRepos.inbox(app)
        val list = targets
        val steward = Steward(inbox, rules, list.map { Destination(it.id, it.label, it.brief) }, names)
        val accepts = list.associate { t ->
            val accept: Accept = { handed -> t.accept(app, handed) }
            t.id to accept
        }
        val dispatcher = Dispatcher(IntakeRepos.handoffs(app), accepts, names)
        val scheduler = StewardScheduler(scope, work = {
            val r = steward.runOnce()
            dispatcher.runOnce()
            r is Steward.Result.Done && r.more
        })
        val pipeline = Pipeline.standard(rules, inbox, reread = Intake::reread, pending = scheduler::poke)
        return Wired(pipeline, scheduler)
    }

    /** 过一遍流水线，都过完才返回。返回落没落进收件箱（新存、补规则、改判都算） */
    internal suspend fun receive(context: Context, notice: Notice, lastLook: Boolean): Boolean =
        wired(context).pipeline.run(notice, lastLook)?.stored != null

    /**
     * 冷启动、切到新链路时：清掉办完 30 天的，叫一下管家 —— 上次进程被杀时没办完的待办、没交成的交接接着办，
     * 等真正文等过了 10 分钟的遮蔽版也放出来
     */
    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (!NoticeRouting.hub(app)) return@launch
            runCatching { IntakeRepos.inbox(app).purgeDone(System.currentTimeMillis() - InboxPolicy.KEEP_DAYS * DAY) }
            wired(app).scheduler.poke()
        }
    }

    private const val DAY = 24 * 60 * 60 * 1000L
}

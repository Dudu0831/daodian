package com.abc.daodian

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.abc.daodian.assistant.conversation.ContextTrace
import com.abc.daodian.assistant.memory.MemoryBook
import com.abc.daodian.assistant.plugin.AssistantPlugins
import com.abc.daodian.assistant.provider.ProviderStore
import com.abc.daodian.assistant.quick.QuickAddActivity
import com.abc.daodian.intake.NoticeHub
import com.abc.daodian.runtime.AgentRuntime
import com.abc.daodian.shared.navigation.Launch
import com.abc.daodian.shell.diagnostics.CrashLog
import com.abc.daodian.shell.entry.ShellEntries
import com.abc.daodian.update.DailyCheck

/**
 * 只做装配：把通知管家的去处、agent 花名册、模块清单装进各自的注册表，挨个做冷启动要做的事
 * （重排闹钟、排巡检、排对账、催监听……）
 */
class DaodianApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 最先装：后面哪一步崩了都要记下来（内测的人那边只能靠「导出诊断」带出来）
        CrashLog.install(this)
        // 系统来绑监听之前去处清单就得在：监听一连上就开始收通知。各模块交出来的那一项，照模块顺序
        NoticeHub.install(MODULES.mapNotNull { it.notice })
        // debug 包里花名册、工具、问人三样对不上就崩，当场发现。后台的几个 agent 都有兜底的 catch，
        // 光抛会被吃掉、只落成一条失败记录，所以扔到主线程上再抛一次 —— 前台、后台一样崩
        AgentRuntime.install(
            agents = AGENTS,
            models = ProviderStore.source(this),
            memory = { MemoryBook.get(this) },
            tracer = { agent, preamble, sent, request -> ContextTrace.log(this, agent, preamble, sent, request) },
            strict = BuildConfig.DEBUG,
            onMisconfigured = { e -> Handler(Looper.getMainLooper()).post { throw e } }
        )
        // 两份插口各自也带着 id，要和模块的对得上
        MODULES.forEach { m ->
            m.assistant?.let { check(it.id == m.id) { "「${m.label}」给助手的那份 id 是 ${it.id}，不是 ${m.id}" } }
            m.shell?.let { check(it.id == m.id) { "「${m.label}」给壳的那份 id 是 ${it.id}，不是 ${m.id}" } }
        }
        AssistantPlugins.install(MODULES.mapNotNull { it.assistant })
        ShellEntries.install(MODULES.mapNotNull { it.shell })
        // 小组件拉起速记用（提醒模块不 import 助手）
        Launch.install(quickAdd = QuickAddActivity::class.java)
        MODULES.forEach { it.onAppStart(this) }
        // 每天几个钟点后台查更新：冷启动排一遍（release 包才排）
        runCatching { DailyCheck.arm(this) }
    }
}

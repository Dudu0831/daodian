package com.abc.daodian

import android.app.Application
import com.abc.daodian.agent.diagnostics.CrashLog
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.intake.Intake

/** 只做装配：把订阅者、模块清单装进各自的注册表，挨个做冷启动要做的事（重排闹钟、排巡检、排对账、催监听……） */
class DaodianApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 最先装：后面哪一步崩了都要记下来（内测的人那边只能靠「导出诊断」带出来）
        CrashLog.install(this)
        // 系统来绑监听之前订阅者就得在：监听一连上就开始分通知
        Intake.install(SUBSCRIBERS)
        FeatureRegistry.install(FEATURES)
        FEATURES.forEach { it.onAppStart(this) }
    }
}

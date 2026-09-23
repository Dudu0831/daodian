package com.abc.daodian

import android.app.Application
import com.abc.daodian.agent.feature.FeatureRegistry

/** 只做装配：把模块清单装进注册表，挨个做冷启动要做的事（重排闹钟、排巡检、排对账……） */
class DaodianApp : Application() {

    override fun onCreate() {
        super.onCreate()
        FeatureRegistry.install(FEATURES)
        FEATURES.forEach { it.onAppStart(this) }
    }
}

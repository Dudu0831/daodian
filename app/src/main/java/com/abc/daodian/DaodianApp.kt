package com.abc.daodian

import android.app.Application
import android.util.Log
import com.abc.daodian.notify.Notifier
import com.abc.daodian.schedule.Rescheduler
import com.abc.daodian.schedule.SweepWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DaodianApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        SweepWorker.enqueue(this)

        // 冷启动也当作一次重排触发源 —— 被强杀后用户点开 app 就是最好的自愈时机
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { Rescheduler(this@DaodianApp).rescheduleAll() }
                .onFailure { Log.e("Daodian/App", "启动重排失败", it) }
        }
    }
}

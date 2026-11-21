package com.abc.daodian.intake

import android.content.Context
import java.io.File

/**
 * 0.2.0 通知管家切换前留在手机上的旧文件，没有代码再读写：派活收到的每一句（`relay.db`）、
 * 以前各模块勾的 app 和派活名单（两个 `.preferences_pb`）、debug 包的第 6 步开关和派活 trace。
 * 0.2.2 起冷启动删一次，删过了再跑什么都不做。0.2.4 删掉这个文件（`docs/cleanup.md`）。
 */
internal object LegacyFiles {

    fun clear(context: Context) {
        runCatching { context.deleteDatabase("relay.db") }
        val datastore = File(context.filesDir, "datastore")
        listOf("intake.preferences_pb", "relay.preferences_pb", "notice_routing.preferences_pb")
            .forEach { runCatching { File(datastore, it).delete() } }
        runCatching { File(context.filesDir, "trace_relay.txt").delete() }
    }
}

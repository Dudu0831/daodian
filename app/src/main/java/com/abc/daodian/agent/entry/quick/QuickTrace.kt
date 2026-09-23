package com.abc.daodian.agent.entry.quick

import android.content.Context
import com.abc.daodian.BuildConfig
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 桌面速记的调试流水账，只在 debug 包里写。
 *
 * 这台荣耀 ROM 屏蔽第三方 app 的 logcat（见 CLAUDE.md），识别服务回了什么、
 * 桌面有没有给墨印位置、纸为什么关了，只能落到文件里再读：
 *
 *     adb shell run-as com.abc.daodian.debug cat files/quick_trace.txt
 *
 * 超过 64KB 就从头写，不会无限长。
 */
object QuickTrace {

    private const val FILE = "quick_trace.txt"
    private const val MAX_BYTES = 64 * 1024
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun log(context: Context, message: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val file = File(context.applicationContext.filesDir, FILE)
            if (file.length() > MAX_BYTES) file.delete()
            file.appendText("${LocalTime.now().format(clock)} $message\n")
        }
    }

    /**
     * 慢放倍数：files/slowmo 里写个数字（比如 8），桌面速记的展开 / 收起就慢那么多倍，
     * 好一帧一帧截图看接缝。文件不在就是 1。只在 debug 包里生效。
     *
     *     adb shell run-as com.abc.daodian.debug sh -c 'echo 8 > files/slowmo'
     */
    fun slowMo(context: Context): Int {
        if (!BuildConfig.DEBUG) return 1
        return runCatching { File(context.applicationContext.filesDir, "slowmo").readText().trim().toInt() }
            .getOrDefault(1)
            .coerceIn(1, 20)
    }
}

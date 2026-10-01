package com.abc.daodian.agent.diagnostics

import android.content.Context
import android.os.Build
import com.abc.daodian.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩了先把堆栈写进 `files/crash/`，再交给系统原来的处理（照样弹「已停止运行」、照样杀进程）。
 * 这台 ROM 屏蔽第三方 app 的 logcat、release 包又不能 run-as，内测的人那边崩了只能靠这个，
 * 由「导出诊断」带出来（[Diagnostics]）。只留最近 [KEEP] 份。
 */
object CrashLog {

    private const val KEEP = 10

    fun install(context: Context) {
        val dir = dir(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(dir, thread, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** 最近的几份，新的在前 */
    fun recent(context: Context): List<File> =
        dir(context).listFiles()?.sortedByDescending { it.name }.orEmpty()

    /** [since] 之后崩过几次 */
    fun countSince(context: Context, since: Long): Int = recent(context).count { it.lastModified() >= since }

    private fun dir(context: Context) = File(context.filesDir, "crash")

    private fun write(dir: File, thread: Thread, e: Throwable) {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
        File(dir, "crash-$now.txt").writeText(
            "时刻 ${Diagnostics.time(now)}\n" +
                "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）${if (BuildConfig.DEBUG) " debug" else ""}\n" +
                "机型 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
                "线程 ${thread.name}\n\n$trace"
        )
        dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }
}

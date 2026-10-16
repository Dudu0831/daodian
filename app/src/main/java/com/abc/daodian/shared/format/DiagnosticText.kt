package com.abc.daodian.shared.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 导出诊断里的写法。壳的诊断页（`shell/diagnostics/Diagnostics`）和各模块交上来的那一段同一个写法 */
object DiagnosticText {

    private val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    /** 「10-15 19:20:01」，没有就是「—」 */
    fun time(millis: Long?): String = millis?.let { clock.format(Instant.ofEpochMilli(it)) } ?: "—"
}

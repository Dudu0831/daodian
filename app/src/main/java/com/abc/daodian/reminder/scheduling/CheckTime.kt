package com.abc.daodian.reminder.scheduling

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

// 文件名还叫 day_tasks：存在手机上的名字不改（CLAUDE.md「挪包之后」那条）
private val Context.checkTimeStore by preferencesDataStore("day_tasks")

/**
 * 收尾时刻：设置里「没说几点的，几点提醒」，默认 20:00。不定钟点的正点、提前提醒、「每天再提」都在这个点（§4.6）。
 * 改了它，排着的那几下由 [Rescheduler.realignAll] 挪过去
 */
object CheckTime {

    val DEFAULT: LocalTime = LocalTime.of(20, 0)

    private val KEY = stringPreferencesKey("check_time")

    fun flow(context: Context): Flow<LocalTime> =
        context.applicationContext.checkTimeStore.data.map { p ->
            p[KEY]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: DEFAULT
        }

    suspend fun get(context: Context): LocalTime = flow(context).first()

    suspend fun save(context: Context, time: LocalTime) {
        context.applicationContext.checkTimeStore.edit { it[KEY] = time.toString() }
    }
}

package com.abc.daodian.ledger.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.ledgerDataStore by preferencesDataStore("ledger")

/** 记账的几个开关。设置页改它们 */
object LedgerSettings {

    /** 默认每 3 小时看一眼有没有待整理的（有才叫模型） */
    const val DEFAULT_ORGANIZE_HOURS = 3
    val ORGANIZE_CHOICES = listOf(1, 3, 6, 12)

    /** 每晚对账默认 21:30，和「当天事项收尾」分开 */
    val DEFAULT_CHECK: LocalTime = LocalTime.of(21, 30)

    private val ORGANIZE_HOURS = intPreferencesKey("organize_hours")
    private val CHECK_TIME = stringPreferencesKey("check_time")
    /** 今天已经主动问过了（yyyyMMdd）。一天最多主动问一次 */
    private val ASKED_DAY = intPreferencesKey("asked_day")
    /** 「晚点」推到的时刻 */
    private val SNOOZED_UNTIL = longPreferencesKey("snoozed_until")

    private fun store(context: Context) = context.applicationContext.ledgerDataStore

    fun organizeHoursFlow(context: Context): Flow<Int> =
        store(context).data.map { it[ORGANIZE_HOURS] ?: DEFAULT_ORGANIZE_HOURS }

    suspend fun organizeHours(context: Context): Int = organizeHoursFlow(context).first()

    suspend fun setOrganizeHours(context: Context, hours: Int) {
        store(context).edit { it[ORGANIZE_HOURS] = hours }
    }

    fun checkTimeFlow(context: Context): Flow<LocalTime> =
        store(context).data.map { p -> p[CHECK_TIME]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: DEFAULT_CHECK }

    suspend fun checkTime(context: Context): LocalTime = checkTimeFlow(context).first()

    suspend fun setCheckTime(context: Context, time: LocalTime) {
        store(context).edit { it[CHECK_TIME] = time.withSecond(0).withNano(0).toString() }
    }

    suspend fun askedDay(context: Context): Int = store(context).data.first()[ASKED_DAY] ?: 0

    suspend fun setAskedDay(context: Context, day: Int) {
        store(context).edit { it[ASKED_DAY] = day }
    }

    suspend fun snoozedUntil(context: Context): Long = store(context).data.first()[SNOOZED_UNTIL] ?: 0L

    suspend fun setSnoozedUntil(context: Context, at: Long) {
        store(context).edit { it[SNOOZED_UNTIL] = at }
    }
}

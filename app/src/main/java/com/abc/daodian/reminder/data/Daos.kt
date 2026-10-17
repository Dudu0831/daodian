package com.abc.daodian.reminder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Insert suspend fun insert(reminder: Reminder): Long
    @Update suspend fun update(reminder: Reminder)
    @Delete suspend fun delete(reminder: Reminder)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): Reminder?

    /** 需要占用一个闹钟槽位的全部提醒 —— 重排的输入 */
    @Query("SELECT * FROM reminders WHERE status = 'SCHEDULED' ORDER BY nextTriggerAt ASC")
    suspend fun allScheduled(): List<Reminder>

    /** 待提醒总条数 —— 小组件抬头的「还有 N 条」 */
    @Query("SELECT COUNT(*) FROM reminders WHERE status = 'SCHEDULED'")
    suspend fun countScheduled(): Int

    /** 已经过期但还没响的漏网记录 —— 兜底巡检的输入 */
    @Query("SELECT * FROM reminders WHERE status = 'SCHEDULED' AND nextTriggerAt < :now ORDER BY nextTriggerAt ASC")
    suspend fun overdue(now: Long): List<Reminder>

    /** 墙钟锚定的提醒，时区变更后需要重算而非简单重排。见 §7.1 */
    @Query("SELECT * FROM reminders WHERE status = 'SCHEDULED' AND wallClockAnchored = 1")
    suspend fun wallClockAnchored(): List<Reminder>

    @Query("SELECT * FROM reminders ORDER BY status ASC, nextTriggerAt ASC")
    fun observeAll(): Flow<List<Reminder>>

    @Query("UPDATE reminders SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: ReminderStatus, now: Long)

    @Query("UPDATE reminders SET nextTriggerAt = :at, updatedAt = :now WHERE id = :id")
    suspend fun setNextTrigger(id: Long, at: Long, now: Long)

    /**
     * 重复的定时提醒排到 [at]，稍后记下的那一刻一并作废。响完排下一次、稍后着点「完成」都走这里。
     * 见 [com.abc.daodian.reminder.scheduling.Series]
     */
    @Query("UPDATE reminders SET nextTriggerAt = :at, seriesNextAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun advance(id: Long, at: Long, now: Long)

    /**
     * 稍后：这一次挪到 [at]，保持 SCHEDULED。[seriesNextAt] 是重复提醒这一串本来的下一次，一次性的传 null。
     * 见 [com.abc.daodian.reminder.scheduling.Series]
     */
    @Query("UPDATE reminders SET status = 'SCHEDULED', nextTriggerAt = :at, seriesNextAt = :seriesNextAt, updatedAt = :now WHERE id = :id")
    suspend fun snooze(id: Long, at: Long, seriesNextAt: Long?, now: Long)

    /** 时区变了、稍后着的重复提醒：稍后那一刻不动，重算的是这一串本来的下一次。见 Rescheduler.recomputeForTimeZone */
    @Query("UPDATE reminders SET seriesNextAt = :at, updatedAt = :now WHERE id = :id")
    suspend fun setSeriesNext(id: Long, at: Long, now: Long)
}

@Dao
interface FireLogDao {
    @Insert suspend fun insert(log: FireLog): Long

    @Query("SELECT * FROM fire_log ORDER BY firedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<FireLog>>

    @Query("SELECT COUNT(*) FROM fire_log WHERE source != 'ALARM'")
    fun observeNonAlarmCount(): Flow<Int>

    @Query("DELETE FROM fire_log")
    suspend fun clear()
}

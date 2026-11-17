package com.abc.daodian.reminder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

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

    @Query("SELECT * FROM reminders ORDER BY status ASC, nextTriggerAt ASC")
    fun observeAll(): Flow<List<Reminder>>

    @Query("UPDATE reminders SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: ReminderStatus, now: Long)

    /**
     * 排到下一下（[com.abc.daodian.reminder.scheduling.Beat]）：这一次是哪天哪个钟点、哪一种、第几次催、哪一刻。
     * 响完、点完成、改收尾时刻、换时区都走这里；稍后作废，回到 SCHEDULED
     */
    @Query(
        "UPDATE reminders SET day = :day, slot = :slot, phase = :phase, nagCount = :nagCount, nextTriggerAt = :at, " +
            "snoozed = 0, status = 'SCHEDULED', updatedAt = :now WHERE id = :id"
    )
    suspend fun arm(id: Long, day: LocalDate, slot: String?, phase: Phase, nagCount: Int, at: Long, now: Long)

    /** 稍后：刚响的那一下（哪天哪个钟点、哪一种、第几次催）挪到 [at] 再响一次，保持 SCHEDULED */
    @Query(
        "UPDATE reminders SET day = :day, slot = :slot, phase = :phase, nagCount = :nagCount, nextTriggerAt = :at, " +
            "snoozed = 1, status = 'SCHEDULED', updatedAt = :now WHERE id = :id"
    )
    suspend fun snooze(id: Long, day: LocalDate, slot: String?, phase: Phase, nagCount: Int, at: Long, now: Long)
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

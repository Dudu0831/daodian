package com.abc.daodian.reminder.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * v4 → v5：两种提醒（定时 / 当天事项）换成一种（§4.6）。整表换成新的几列，逐行换算；顺手建清单的表（§4.5）。
 *
 * **排期一点不动**：每行的 [Reminder.nextTriggerAt] 原样留着，所以手机上排着的闹钟照旧有效，不用重排。
 * 换算只是把「它为什么排在那一刻」写成新字段（哪天、几点、这一下是哪种），见 [convert]。
 * 搬完行为和以前一样，只有一处：稍后着的重复提醒放弃这次稍后，回到这一串本来的下一次。
 */
object Migration4To5 : Migration(4, 5) {

    // 和 Room 照 [Reminder] 生成的建表语句一字不差（schemas/…/5.json），不然打开时校验不过
    private const val CREATE_REMINDERS =
        "CREATE TABLE IF NOT EXISTS `reminders_v5` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
            "`note` TEXT, `rawInput` TEXT NOT NULL, `nextTriggerAt` INTEGER NOT NULL, `rrule` TEXT, `zoneId` TEXT NOT NULL, " +
            "`wallClockAnchored` INTEGER NOT NULL, `status` TEXT NOT NULL, `parsedBy` TEXT, `day` TEXT NOT NULL, `times` TEXT, " +
            "`remindBefore` TEXT, `ring` TEXT NOT NULL, `nag` TEXT NOT NULL, `slot` TEXT, `phase` TEXT NOT NULL, " +
            "`nagCount` INTEGER NOT NULL, `snoozed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"

    private const val CREATE_ITEMS =
        "CREATE TABLE IF NOT EXISTS `reminder_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `reminderId` INTEGER NOT NULL, " +
            "`text` TEXT NOT NULL, `done` INTEGER NOT NULL, `position` INTEGER NOT NULL)"

    override fun migrate(db: SupportSQLiteDatabase) {
        val here = ZoneId.systemDefault()
        db.execSQL(CREATE_REMINDERS)
        db.query(
            "SELECT id, title, note, rawInput, nextTriggerAt, rrule, zoneId, localTime, wallClockAnchored, status, parsedBy, " +
                "dueDay, gentle, seriesNextAt, nudges, endsOnDay, createdAt, updatedAt FROM reminders"
        ).use { c ->
            while (c.moveToNext()) {
                val v4 = V4Row(
                    nextTriggerAt = c.getLong(4),
                    rrule = c.stringOrNull(5),
                    zoneId = c.getString(6),
                    localTime = c.stringOrNull(7),
                    wallClockAnchored = c.getInt(8) != 0,
                    dueDay = c.stringOrNull(11),
                    gentle = c.getInt(12) != 0,
                    seriesNextAt = if (c.isNull(13)) null else c.getLong(13),
                    nudges = c.stringOrNull(14),
                    endsOnDay = c.getInt(15) != 0
                )
                val v5 = convert(v4, here)
                val row = ContentValues().apply {
                    put("id", c.getLong(0))
                    put("title", c.getString(1))
                    put("note", c.stringOrNull(2))
                    put("rawInput", c.getString(3))
                    put("nextTriggerAt", v5.nextTriggerAt)
                    put("rrule", v4.rrule)
                    put("zoneId", v4.zoneId)
                    put("wallClockAnchored", if (v5.wallClockAnchored) 1 else 0)
                    put("status", c.getString(9))
                    put("parsedBy", c.stringOrNull(10))
                    put("day", v5.day.toString())
                    put("times", v5.times)
                    put("remindBefore", v5.remindBefore)
                    put("ring", v5.ring.name)
                    put("nag", v5.nag.name)
                    put("slot", v5.slot)
                    put("phase", v5.phase.name)
                    put("nagCount", v5.nagCount)
                    put("snoozed", 0)
                    put("createdAt", c.getLong(16))
                    put("updatedAt", c.getLong(17))
                }
                db.insert("reminders_v5", SQLiteDatabase.CONFLICT_ABORT, row)
            }
        }
        db.execSQL("DROP TABLE reminders")
        db.execSQL("ALTER TABLE reminders_v5 RENAME TO reminders")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_nextTriggerAt` ON `reminders` (`nextTriggerAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_status` ON `reminders` (`status`)")
        db.execSQL(CREATE_ITEMS)
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminder_items_reminderId` ON `reminder_items` (`reminderId`)")
    }

    private fun android.database.Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
}

/** v4 一行里换算要看的那几列 */
internal data class V4Row(
    val nextTriggerAt: Long,
    val rrule: String?,
    val zoneId: String,
    val localTime: String?,
    val wallClockAnchored: Boolean,
    val dueDay: String?,
    val gentle: Boolean,
    val seriesNextAt: Long?,
    val nudges: String?,
    val endsOnDay: Boolean
)

/** 换算出来的新几列 */
internal data class V5Cols(
    val nextTriggerAt: Long,
    val wallClockAnchored: Boolean,
    val day: LocalDate,
    val times: String?,
    val remindBefore: String?,
    val ring: Ring,
    val nag: Nag,
    val slot: String?,
    val phase: Phase,
    val nagCount: Int
)

/**
 * 一行怎么换。[here] 是迁移时手机所在的时区（不定钟点的、跟着人走的按它）。
 *
 * - 当天事项（有 `dueDay`）→ 不定钟点、只发通知；排着的那一刻在那天之前是提前提醒，在那天之后是没做完在催（拖了几天就是第几次）。
 *   一次性的：`endsOnDay` → 不催，别的 → 每天再提（以前的顺延）；提前提醒的日期换成提前几天。重复的不催（以前就是这次算了、翻到下一次）。
 * - 定时的 → 它那一个钟点，`gentle` → 只发通知，不催。稍后着的重复提醒回到 `seriesNextAt`（这一串本来的下一次）
 */
internal fun convert(v4: V4Row, here: ZoneId): V5Cols {
    val due = v4.dueDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (due != null) {
        val ringDay = Instant.ofEpochMilli(v4.nextTriggerAt).atZone(here).toLocalDate()
        val once = v4.rrule == null
        val phase = when {
            ringDay.isBefore(due) -> Phase.NUDGE
            ringDay.isAfter(due) && once -> Phase.NAG
            else -> Phase.MAIN
        }
        val offsets = if (once) {
            v4.nudges?.split(',')?.mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
                ?.map { ChronoUnit.DAYS.between(it, due).toInt() }.orEmpty()
        } else emptyList()
        return V5Cols(
            nextTriggerAt = v4.nextTriggerAt,
            wallClockAnchored = true,
            day = due,
            times = null,
            remindBefore = remindBeforeColumn(offsets),
            ring = Ring.NOTICE,
            nag = if (once && !v4.endsOnDay) Nag.DAILY else Nag.NONE,
            slot = null,
            phase = phase,
            nagCount = if (phase == Phase.NAG) ChronoUnit.DAYS.between(due, ringDay).toInt() else 0
        )
    }
    val at = v4.seriesNextAt ?: v4.nextTriggerAt
    val zone = if (v4.wallClockAnchored) here else runCatching { ZoneId.of(v4.zoneId) }.getOrDefault(here)
    val local = Instant.ofEpochMilli(at).atZone(zone)
    val clock = (v4.localTime?.takeIf { v4.wallClockAnchored }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        ?: local.toLocalTime()).withSecond(0).withNano(0)
    val slot = timesColumn(listOf(clock))
    return V5Cols(
        nextTriggerAt = at,
        wallClockAnchored = v4.wallClockAnchored,
        day = local.toLocalDate(),
        times = slot,
        remindBefore = null,
        ring = if (v4.gentle) Ring.NOTICE else Ring.ALARM,
        nag = Nag.NONE,
        slot = slot,
        phase = Phase.MAIN,
        nagCount = 0
    )
}

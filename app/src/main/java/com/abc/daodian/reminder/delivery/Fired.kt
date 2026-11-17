package com.abc.daodian.reminder.delivery

import android.content.Intent
import com.abc.daodian.reminder.data.Phase
import com.abc.daodian.reminder.data.Reminder
import java.time.LocalDate

/**
 * 一条通知（或全屏页）说的是哪一下：哪天、哪个钟点、哪一种、第几次催。发通知时记在按钮上，
 * 按钮回来时拿它对 —— 响完这条往往已经排到别的次了（重复的翻到下一次），「完成」不能把下一次也算掉，
 * 「稍后」要再响的是这一下
 */
data class Fired(val day: LocalDate, val slot: String?, val phase: Phase, val nagCount: Int) {

    /** 这条眼下还停在这一次（没翻过去） */
    fun sameOccurrence(r: Reminder): Boolean = r.day == day && r.slot == slot

    fun into(intent: Intent): Intent = intent
        .putExtra(EXTRA_DAY, day.toString())
        .putExtra(EXTRA_SLOT, slot)
        .putExtra(EXTRA_PHASE, phase.name)
        .putExtra(EXTRA_NAG_COUNT, nagCount)

    companion object {
        private const val EXTRA_DAY = "fired_day"
        private const val EXTRA_SLOT = "fired_slot"
        private const val EXTRA_PHASE = "fired_phase"
        private const val EXTRA_NAG_COUNT = "fired_nag_count"

        fun of(r: Reminder) = Fired(r.day, r.slot, r.phase, r.nagCount)

        /** 没带的（老版本发的通知还挂着）是 null：只认这条眼下那一次 */
        fun from(intent: Intent): Fired? {
            val day = intent.getStringExtra(EXTRA_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val phase = intent.getStringExtra(EXTRA_PHASE)?.let { runCatching { Phase.valueOf(it) }.getOrNull() } ?: return null
            return Fired(day, intent.getStringExtra(EXTRA_SLOT), phase, intent.getIntExtra(EXTRA_NAG_COUNT, 0))
        }
    }
}

package com.abc.daodian.reminder.delivery

import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.Phase
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.Ring

/**
 * 通知和全屏页上的按钮（§4.6）。
 * [DONE] 和 [OK] 发的是同一个「完成」（只算刚响的这一次）；不催的响过就已经落成过去或翻到下一次了，
 * 点不点都一样，所以不叫「完成」—— 响铃的总得有一颗停铃，叫「知道了」，只发通知的就不挂
 */
enum class Button(val label: String) { DONE("完成"), OK("知道了"), SNOOZE("稍后 10 分钟") }

/** 提前那次只是提一下，不挂；稍后谁都有；设了催才有「完成」 */
fun buttonsOf(r: Reminder): List<Button> = when {
    r.phase == Phase.NUDGE -> emptyList()
    r.nag != Nag.NONE -> listOf(Button.DONE, Button.SNOOZE)
    r.ring == Ring.ALARM -> listOf(Button.OK, Button.SNOOZE)
    else -> listOf(Button.SNOOZE)
}

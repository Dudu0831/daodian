package com.abc.daodian

import com.abc.daodian.agent.feature.Feature
import com.abc.daodian.intake.IntakeFeature
import com.abc.daodian.intake.NoticeSubscriber
import com.abc.daodian.ledger.LedgerFeature
import com.abc.daodian.ledger.capture.LedgerCapture
import com.abc.daodian.reminder.ReminderFeature
import com.abc.daodian.reminder.relay.Relay

/**
 * 全 app 唯一的模块清单。顺序有意义：提示词按这个顺序拼（改顺序 = system 变了，前缀缓存失效一次），
 * 抽屉里的纸、设置页的组也按这个顺序摆。加模块 = 这里加一行。见 DESIGN.md §2.2
 *
 * 通知监听层排最后：它没有提示词，只在设置页里占一组「通知监听」，放在各模块的组后面。
 */
val FEATURES: List<Feature> = listOf(ReminderFeature, LedgerFeature, IntakeFeature)

/**
 * 要听通知的模块。你在「通知监听」里给谁勾了哪个 app，那个 app 的通知就交给谁。
 * 要听通知 = 实现 [NoticeSubscriber]、这里加一行。见 DESIGN.md §2.3
 */
val SUBSCRIBERS: List<NoticeSubscriber> = listOf(LedgerCapture, Relay)

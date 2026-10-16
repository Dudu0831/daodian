package com.abc.daodian

import com.abc.daodian.intake.IntakeModule
import com.abc.daodian.intake.NoticeSubscriber
import com.abc.daodian.ledger.LedgerModule
import com.abc.daodian.ledger.capture.LedgerCapture
import com.abc.daodian.module.Module
import com.abc.daodian.reminder.ReminderModule
import com.abc.daodian.reminder.relay.Relay

/**
 * 全 app 唯一的模块清单。顺序有意义：助手的工具按这个顺序排（改顺序 = 发给模型的工具列表变了，前缀缓存失效一次），
 * 抽屉里的纸、设置页的组也按这个顺序摆。加模块 = 写一个 [Module]、这里加一行；它给助手的工具要在助手的提示词里写上用法
 * （`assistant/AssistantPrompt`，模块不往里追加段落）。见 DESIGN.md §2.2、§6.3
 *
 * 通知监听层排最后：它只给壳，在「权限与监听」页里占几组，放在各模块的体检项后面。
 */
val MODULES: List<Module> = listOf(ReminderModule, LedgerModule, IntakeModule)

/**
 * 要听通知的模块。你给谁勾了哪个 app（设置 → 权限与监听 → 谁在听），那个 app 的通知就交给谁。
 * 要听通知 = 实现 [NoticeSubscriber]、这里加一行。见 DESIGN.md §2.3
 */
val SUBSCRIBERS: List<NoticeSubscriber> = listOf(LedgerCapture, Relay)

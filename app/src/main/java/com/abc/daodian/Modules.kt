package com.abc.daodian

import com.abc.daodian.intake.IntakeModule
import com.abc.daodian.ledger.LedgerModule
import com.abc.daodian.module.Module
import com.abc.daodian.reminder.ReminderModule
import com.abc.daodian.whatsnew.WhatsNewModule

/**
 * 全 app 唯一的模块清单。顺序有意义：助手的工具按这个顺序排（改顺序 = 发给模型的工具列表变了，前缀缓存失效一次），
 * 抽屉里的纸、设置页的组也按这个顺序摆。加模块 = 写一个 [Module]、这里加一行；它给助手的工具要在助手的提示词里写上用法
 * （`assistant/AssistantPrompt`，模块不往里追加段落）。见 DESIGN.md §2.2、§6.3
 *
 * 通知监听层排在业务模块后面：它给助手建规则的几个工具，在「权限」页里占一组，放在各模块的体检项后面。
 * 新功能弹窗最后：只有盖在上面的那一层，不给助手、不占设置行。
 */
val MODULES: List<Module> = listOf(ReminderModule, LedgerModule, IntakeModule, WhatsNewModule)


package com.abc.daodian

import com.abc.daodian.agent.feature.Feature
import com.abc.daodian.ledger.LedgerFeature
import com.abc.daodian.reminder.ReminderFeature

/**
 * 全 app 唯一的模块清单。顺序有意义：提示词按这个顺序拼（改顺序 = system 变了，前缀缓存失效一次），
 * 抽屉里的纸、设置页的组也按这个顺序摆。加模块 = 这里加一行。见 PROJECT_STRUCTURE.md
 */
val FEATURES: List<Feature> = listOf(ReminderFeature, LedgerFeature)

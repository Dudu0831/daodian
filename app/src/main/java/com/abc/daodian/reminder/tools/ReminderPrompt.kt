package com.abc.daodian.reminder.tools

/**
 * 对话 agent 的 system 里关于提醒的一段，接在基础提示词后面。原样从当初那份整段提示词里切出来，
 * 按原顺序拼回去逐字节不变（前缀缓存）—— 改措辞前先想清楚要不要付一次缓存失效。
 */
object ReminderPrompt {

    val RULES = """
## 提醒的规则
- 说了具体钟点：allDay 填 false。
- 只说了哪天、没说几点（「今天把报销交了」「明天买菜」「周五前交周报」「每天背单词」）是当天事项：
  allDay 填 true，firstTriggerAt 填那天 00:00，不要追问几点 —— app 会在那天晚上统一提醒、没做完顺延。
  连哪天都没说（「记得买菜」）就算今天。「周五前」这类截止日填截止那天。
- 每月 31 号这种落在小月时，顺延到该月最后一天，不跳过。
""".trimIndent()
}

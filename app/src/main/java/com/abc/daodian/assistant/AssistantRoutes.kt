package com.abc.daodian.assistant

import com.abc.daodian.shared.navigation.Launch

/**
 * 助手自己的两页。壳（`shell/AppNavHost`）注册它们；速记、记忆的痕、小组件框要用这两个名字，
 * 助手又不 import 壳，所以常量放在这里。
 */
object AssistantRoutes {
    /** 对话页，也是起点。字是 `"chat"`，存在通知、小组件的 PendingIntent 里，不改 */
    const val HOME = Launch.HOME

    /** 记忆管理页（§6.9）。设置首页「记忆」那一行、对话里「记住了」的痕都到这里 */
    const val MEMORY = "memory"
}

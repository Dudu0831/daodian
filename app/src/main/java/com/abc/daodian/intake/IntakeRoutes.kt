package com.abc.daodian.intake

/**
 * 通知监听层的页面路由。页面在 [com.abc.daodian.intake.presentation.IntakeShell] 注册。
 * 使用权、连没连着、谁在听不是自己一页，在壳的「权限与监听」页里（[com.abc.daodian.intake.presentation.IntakeShell.PermissionSection]），
 * 勾选页只从那里「谁在听」进 —— 模块页不往这边跳。
 */
object IntakeRoutes {
    /** 某个订阅者听哪些 app */
    const val APPS = "intake/apps/{id}"

    fun apps(subscriberId: String) = "intake/apps/$subscriberId"

    /** 听通知的规则（新链路，DESIGN.md §12.2） */
    const val RULES = "intake/rules"

    const val RULE = "intake/rule/{id}"

    fun rule(id: Long) = "intake/rule/$id"

    /** 收到的通知：收件箱的记录（§12.3） */
    const val NOTICES = "intake/notices"

    const val NOTICE = "intake/notice/{id}"

    fun notice(id: Long) = "intake/notice/$id"
}

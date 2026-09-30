package com.abc.daodian.intake

/**
 * 通知监听层的页面路由。页面在 [IntakeFeature] 注册。
 * 使用权、连没连着、谁在听不是自己一页，在壳的「权限与监听」页里（[IntakeFeature.PermissionSection]），
 * 勾选页只从那里「谁在听」进 —— 模块页不往这边跳。
 */
object IntakeRoutes {
    /** 某个订阅者听哪些 app */
    const val APPS = "intake/apps/{id}"

    fun apps(subscriberId: String) = "intake/apps/$subscriberId"
}

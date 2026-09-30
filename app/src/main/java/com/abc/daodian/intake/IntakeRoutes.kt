package com.abc.daodian.intake

/**
 * 通知监听层的页面路由。页面在 [IntakeFeature] 注册；模块要跳过来（各自设置页里「听哪些 app」）
 * 也用这里的字符串，所以放在核心里、不挂在接头上。
 * 使用权、连没连着、谁在听不是自己一页，在壳的「权限与监听」页里（[IntakeFeature.PermissionSection]）。
 */
object IntakeRoutes {
    /** 某个订阅者听哪些 app */
    const val APPS = "intake/apps/{id}"

    fun apps(subscriberId: String) = "intake/apps/$subscriberId"
}

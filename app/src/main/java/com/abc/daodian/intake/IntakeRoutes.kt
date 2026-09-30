package com.abc.daodian.intake

/**
 * 通知监听层的页面路由。页面在 [IntakeFeature] 注册；模块要跳过来（设置里「听哪些 app」、抓取页上「通知监听」）
 * 也用这里的字符串，所以放在核心里、不挂在接头上。
 */
object IntakeRoutes {
    /** 通知使用权、连没连着、重连、扫一遍、谁在听哪些 app */
    const val STATUS = "intake"

    /** 某个订阅者听哪些 app */
    const val APPS = "intake/apps/{id}"

    fun apps(subscriberId: String) = "intake/apps/$subscriberId"
}

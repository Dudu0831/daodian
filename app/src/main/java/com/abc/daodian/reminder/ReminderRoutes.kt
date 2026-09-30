package com.abc.daodian.reminder

/**
 * 提醒各页的路由。页面在 [ReminderFeature.routes] 注册；通知、小组件、痕要拉起某一页时也用这里的字符串
 * （经 shared/navigation/Launch），所以放在一个谁都能引的地方，不挂在接头上。
 */
object ReminderRoutes {
    const val LIST = "reminder/list"
    const val EDIT = "reminder/edit?id={id}"
    const val LOG = "reminder/log"

    /** 提醒的设置页：当天事项收尾、派活、投递日志。设置首页「提醒」那一行进 */
    const val SETTINGS = "reminder/settings"

    /** 她发来的（派活试验版）：每一句办成了什么、试一句。提醒设置页「派活」那一组进，派活没接住的通知也拉起这里 */
    const val RELAY = "reminder/relay"

    /** 编辑某一条；null = 新建一条（手动填，不经过模型） */
    fun edit(id: Long?) = "reminder/edit?id=${id ?: -1L}"

    const val NEW = "reminder/edit"
}

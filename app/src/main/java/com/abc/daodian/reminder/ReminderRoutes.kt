package com.abc.daodian.reminder

/**
 * 提醒各页的路由。页面在 [com.abc.daodian.reminder.presentation.ReminderShell.routes] 注册；通知、小组件、痕要拉起某一页时也用这里的字符串
 * （经 shared/navigation/Launch），所以放在一个谁都能引的地方，不挂在接头上。
 */
object ReminderRoutes {
    const val LIST = "reminder/list"
    const val EDIT = "reminder/edit?id={id}"
    /** 响得准不准：最近几次投递的统计。路由名照旧 */
    const val LOG = "reminder/log"

    /** 提醒的设置页：当天事项收尾、响得准不准。设置首页「提醒」那一行进 */
    const val SETTINGS = "reminder/settings"

    /** 编辑某一条；null = 新建一条（手动填，不经过模型） */
    fun edit(id: Long?) = "reminder/edit?id=${id ?: -1L}"

    const val NEW = "reminder/edit"
}

package com.abc.daodian.reminder

/**
 * 提醒各页的路由。页面在 [com.abc.daodian.reminder.presentation.ReminderShell.routes] 注册；通知、小组件、痕要拉起某一页时也用这里的字符串
 * （经 shared/navigation/Launch），所以放在一个谁都能引的地方，不挂在接头上。
 */
object ReminderRoutes {
    const val LIST = "reminder/list"
    const val EDIT = "reminder/edit?id={id}"
    const val LOG = "reminder/log"

    /** 提醒的设置页：当天事项收尾、投递日志。设置首页「提醒」那一行进 */
    const val SETTINGS = "reminder/settings"

    /**
     * 以前派活那一页（某个人发来的每一句）。派活删了，**路由留壳**：通知栏里还挂着的旧「派的事没接住」通知存着这个路由，
     * 点了跳规则页。0.2.2 删，连不带人的 [RELAY_OLD] 一起（`docs/cleanup.md`）
     */
    const val RELAY = "reminder/relay/{id}"

    /** 只听一个人那一版「没接住」的通知拉起的，不带人。同上，留壳 */
    const val RELAY_OLD = "reminder/relay"

    /** 编辑某一条；null = 新建一条（手动填，不经过模型） */
    fun edit(id: Long?) = "reminder/edit?id=${id ?: -1L}"

    const val NEW = "reminder/edit"
}

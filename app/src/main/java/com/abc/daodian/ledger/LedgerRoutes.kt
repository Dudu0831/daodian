package com.abc.daodian.ledger

/**
 * 记账页面的路由：三层（总览 → 类别 → 一笔）、设置页。页面在 [com.abc.daodian.ledger.presentation.LedgerShell.routes] 注册；痕、通知要拉起某一页时也用这里的字符串。
 */
object LedgerRoutes {
    const val HOME = "ledger"
    const val CATEGORY = "ledger/category/{top}?income={income}&mode={mode}&day={day}"
    const val TXN = "ledger/txn/{id}"

    /** 记账的设置页：整理 / 对账。设置首页「记账」那一行进 */
    const val SETTINGS = "ledger/settings"

    /** [mode] 是 PeriodMode 的名字，[day] 是 yyyyMMdd */
    fun category(top: Long, income: Boolean, mode: String, day: Int) = "ledger/category/$top?income=$income&mode=$mode&day=$day"

    fun txn(id: Long) = "ledger/txn/$id"

    /** 每晚对账那一轮的 trigger 键（Launch 的 trigger、AppNav.trigger） */
    const val CHECK = "ledger:check"
}

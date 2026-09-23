package com.abc.daodian.agent.shell

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder

/**
 * 模块给界面壳的那一半接头（另一半是 [com.abc.daodian.agent.feature.Feature]）。壳只有框，内容由模块塞：
 * 自己的页面、抽屉里的一张纸、设置页里的一组。见 PROJECT_STRUCTURE.md「界面归属」
 *
 * 模块之间、模块和壳之间跳转只用路由字符串（[open] / [AppNav.open]），不互相 import 页面。
 */
interface FeatureUi {

    /** 自己的页面。路由以模块 id 开头：`reminder/list`、`ledger/txn/{id}` */
    fun NavGraphBuilder.routes(nav: AppNav)

    /** 抽屉里的一张纸（用 shared/ui 的 DrawerPaper，和别的纸长得一样） */
    @Composable
    fun DrawerCard(open: (String) -> Unit) {}

    /** 设置页里的一组或几组，排在「系统权限」后面 */
    @Composable
    fun SettingsSection(open: (String) -> Unit) {}

    /** 设置页顶上体检结论底下补一行（提醒：最近投递准不准） */
    @Composable
    fun HealthNote() {}
}

/** 模块页面能做的跳转 */
interface AppNav {
    fun open(route: String)
    fun back()

    /** 回到对话页；[prefill] 不为空就先填进输入框 */
    fun chat(prefill: String? = null)

    /** 回到对话页，开一轮 app 发起的（[key] 形如 `ledger:check`） */
    fun trigger(key: String)
}

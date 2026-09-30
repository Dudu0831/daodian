package com.abc.daodian.agent.shell

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder

/**
 * 模块给界面壳的那一半接头（另一半是 [com.abc.daodian.agent.feature.Feature]）。壳只有框，内容由模块塞：
 * 自己的页面、抽屉里的一张纸、设置首页的一行、「权限与监听」页里的一组。见 DESIGN.md §2.2「界面归属」
 *
 * 模块之间、模块和壳之间跳转只用路由字符串（[open] / [AppNav.open]），不互相 import 页面。
 */
interface FeatureUi {

    /** 自己的页面。路由以模块 id 开头：`reminder/list`、`ledger/txn/{id}` */
    fun NavGraphBuilder.routes(nav: AppNav)

    /** 抽屉里的一张纸（用 shared/ui 的 DrawerPaper，和别的纸长得一样） */
    @Composable
    fun DrawerCard(open: (String) -> Unit) {}

    /**
     * 设置首页「模块」那一组里的行，每行点进模块自己的设置页。一个模块一行，模块里的小功能（提醒里的派活）
     * 在它自己的设置页里。行上写里面有什么，不写会变的现状 —— 读库的现状打开时会闪、行高会跳（DESIGN.md §08）
     */
    val settingsEntries: List<SettingsEntry> get() = emptyList()

    /** 「权限与监听」页里自己的一组或几组，排在各模块的体检项后面（通知监听层：使用权、监听、谁在听） */
    @Composable
    fun PermissionSection(open: (String) -> Unit) {}

    /** 设置首页「权限与监听」那一行上，自己要说的一句（通知监听层：连着 / 断了）。没什么要说的是 null */
    @Composable
    fun permissionStatus(): PermissionStatus? = null

    /** 「权限与监听」页顶上体检结论底下补一行（提醒：最近投递准不准） */
    @Composable
    fun HealthNote() {}

    /**
     * 问卡上一题底下挂的一块（记账：顺手打标签）。[ref] 是模型在那题上写的「#12」，不是自己的就什么都不画。
     * 只在等你答的时候画；办了什么，「就这样」时由 [com.abc.daodian.agent.feature.Feature.askNote] 报给问卡
     */
    @Composable
    fun AskAddon(ref: String) {}
}

/** 设置首页的一行：[title]、底下一句 [note] 说里面有什么，点了去 [route] */
class SettingsEntry(val title: String, val route: String, val note: String)

/** 「权限与监听」那一行上的一句：[ok] 为 false 时写红字 */
data class PermissionStatus(val text: String, val ok: Boolean)

/** 模块页面能做的跳转 */
interface AppNav {
    fun open(route: String)
    fun back()

    /** 回到对话页；[prefill] 不为空就先填进输入框 */
    fun chat(prefill: String? = null)

    /** 回到对话页，开一轮 app 发起的（[key] 形如 `ledger:check`） */
    fun trigger(key: String)
}

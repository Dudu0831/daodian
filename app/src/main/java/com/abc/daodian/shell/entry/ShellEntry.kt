package com.abc.daodian.shell.entry

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder

/**
 * 一个模块给界面壳的那一份（模块接头 `module/Module` 的 `shell`）。壳只有框，内容由模块塞：
 * 自己的页面、抽屉里的一张纸、设置首页的一行、「权限与监听」页里的一组、体检项、导出诊断里的一段。
 * 见 DESIGN.md §2.2「界面归属」
 *
 * 模块之间、模块和壳之间跳转只用路由字符串（[open] / [AppNav.open]），不互相 import 页面。
 */
interface ShellEntry {

    /** 和模块的 id 一样（「reminder」），路由以它开头。装的时候根目录查一遍 */
    val id: String

    /** 给人看的名字：「提醒」「记账」「通知监听」。「权限与监听」页上写「提醒要的」，导出诊断里是这一段的标题 */
    val label: String

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

    /** 体检项：挂了会让这个模块的核心功能失效的系统权限 */
    fun health(context: Context): List<HealthItem> = emptyList()

    /**
     * 「导出诊断」里这个模块的一段（`shell/diagnostics/Diagnostics`）。内测的人发给开发者看的，
     * **只写状态和数**：响得准不准、排没排上、收了几条、整理成没成。提醒标题、金额、商户、通知原文都不写。没有就是 null
     */
    suspend fun diagnostics(context: Context): String? = null
}

/** 设置首页的一行：[title]、底下一句 [note] 说里面有什么，点了去 [route] */
class SettingsEntry(val title: String, val route: String, val note: String)

/** 「权限与监听」那一行上的一句：[ok] 为 false 时写红字 */
data class PermissionStatus(val text: String, val ok: Boolean)

/**
 * 体检的一项。[ok] = null 是查不到、只能手动设的（MagicOS 的「应用启动管理」）。
 * [fixIntent] 是点这一行跳到哪儿：缺的时候去开，好着的时候也能点进去看、改。
 */
data class HealthItem(
    val label: String,
    val ok: Boolean?,
    val detail: String,
    val fixIntent: Intent?
)

/** 模块页面能做的跳转 */
interface AppNav {
    fun open(route: String)
    fun back()

    /** 回到对话页（助手）；[prefill] 不为空就先填进输入框 */
    fun assistant(prefill: String? = null)

    /** 回到对话页，开一轮 app 发起的（[key] 形如 `ledger:check`） */
    fun trigger(key: String)
}

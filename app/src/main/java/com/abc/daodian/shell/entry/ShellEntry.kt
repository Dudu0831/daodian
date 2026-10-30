package com.abc.daodian.shell.entry

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder

/**
 * 一个模块给界面壳的那一份（模块接头 `module/Module` 的 `shell`）。壳只有框，内容由模块塞：
 * 自己的页面、抽屉里的一张纸、设置首页的一行、「权限」页里的一组、体检项、导出诊断里的一段。
 * 见 DESIGN.md §2.2「界面归属」
 *
 * 模块之间、模块和壳之间跳转只用路由字符串（[open] / [AppNav.open]），不互相 import 页面。
 */
interface ShellEntry {

    /** 和模块的 id 一样（「reminder」），路由以它开头。装的时候根目录查一遍 */
    val id: String

    /** 给人看的名字：「提醒」「记账」「通知管家」。「权限」页上写「提醒要的」，导出诊断里是这一段的标题 */
    val label: String

    /** 自己的页面。路由以模块 id 开头：`reminder/list`、`ledger/txn/{id}` */
    fun NavGraphBuilder.routes(nav: AppNav)

    /** 抽屉里的一张纸（用 shared/ui 的 DrawerPaper，和别的纸长得一样） */
    @Composable
    fun DrawerCard(open: (String) -> Unit) {}

    /**
     * 设置首页「功能」那一组里的行，每行点进模块自己的设置页。一个模块一行，模块里的小功能（提醒里的准不准）
     * 在它自己的设置页里。行上默认只有名字；要写现状的用 [settingsStatus]（DESIGN.md §08）
     */
    val settingsEntries: List<SettingsEntry> get() = emptyList()

    /** 设置首页自己那一行上的现状（通知管家：连着 · 今天 6 条，右边规则条数）。没什么要说的是 null */
    @Composable
    fun settingsStatus(): SettingsStatus? = null

    /** 「权限」页里体检项后面自己的一组（通知管家：通知使用权）。不算进体检结论 */
    @Composable
    fun PermissionSection() {}

    /**
     * 盖在整个 app 上面的一层（新功能弹窗）。壳画在导航上面、对话页和各页都盖得住；
     * 出不出、什么时候收由模块自己管，不出就什么都不画
     */
    @Composable
    fun Overlay() {}

    /** 体检项：挂了会让这个模块的核心功能失效的系统权限 */
    fun health(context: Context): List<HealthItem> = emptyList()

    /**
     * 「导出诊断」里这个模块的一段（`shell/diagnostics/Diagnostics`）。内测的人发给开发者看的，
     * **只写状态和数**：响得准不准、排没排上、收了几条、整理成没成。提醒标题、金额、商户、通知原文都不写。没有就是 null
     */
    suspend fun diagnostics(context: Context): String? = null
}

/** 设置首页的一行：[title]，点了去 [route] */
class SettingsEntry(val title: String, val route: String)

/** 设置首页那一行上的现状：底下一句 [note]（[ok] 为 false 时写红字），右边一个数 [value] */
data class SettingsStatus(val note: String, val ok: Boolean, val value: String? = null)

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

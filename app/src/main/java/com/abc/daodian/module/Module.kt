package com.abc.daodian.module

import android.content.Context
import com.abc.daodian.assistant.skill.AssistantSkill
import com.abc.daodian.shell.entry.ShellEntry

/**
 * 一个模块的接头：它是谁，给助手什么、给壳什么，冷启动要做什么。在根目录 `Modules.kt` 里列一行就接上了。
 * 见 DESIGN.md §2.2「接头」
 *
 * 模块自己能独立运转（提醒断网也要响、记账在后台采集整理），接头只管「交出去什么」。
 * 两份插口各自也带着 id，要和这里的一样（根目录装的时候查）。听通知的订阅者另外在 `Modules.kt` 的 `SUBSCRIBERS` 里列。
 *
 * 这个包只放这一个接口：只有根目录和各模块的接头文件 import 它。
 */
interface Module {

    /** 「reminder」。也是它的路由前缀、trigger 键的前缀 */
    val id: String

    /** 给人看的名字：「提醒」 */
    val label: String

    /** 给助手的：工具、现状、痕、例句、app 发起的一轮。不跟模型打交道的（通知监听层）是 null */
    val assistant: AssistantSkill? get() = null

    /** 给壳的：页面、抽屉卡、设置行、权限页的组、体检、诊断 */
    val shell: ShellEntry? get() = null

    /** 冷启动（包括被闹钟、广播拉起的进程）要做的事。在主线程上调，耗时的自己开协程 */
    fun onAppStart(context: Context) {}
}

package com.abc.daodian.agent.feature

import android.content.Context
import android.content.Intent
import com.abc.daodian.agent.engine.ask.AskNote
import com.abc.daodian.agent.engine.tool.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 一个模块接到 agent 上的唯一接头。agent 只认识它，不知道提醒、账是什么。见 DESIGN.md §2.2「接头」
 *
 * 模块自己能独立运转（提醒断网也要响、记账在后台采集整理），接头只管「给 agent 什么」：
 * 工具、提示词、痕、例句、体检项、app 自己发起的一轮、冷启动要做的事。
 * 界面那一半（页面、抽屉卡、设置组）在 `agent/shell/FeatureUi`。
 *
 * 加一个模块 = 新目录 + 一个实现它的 object + 根目录 `Features.kt` 里加一行。
 */
interface Feature {

    /** 「reminder」。也是它的路由前缀、trigger 键的前缀 */
    val id: String

    /** 给人看的名字：「提醒」「记账」「通知监听」。「权限与监听」页上写「提醒要的」 */
    val label: String

    /**
     * 接在基础提示词后面的一段，按 `Features.kt` 的顺序拼。必须是常量 ——
     * system 要逐字节稳定（前缀缓存），会变的东西放进那一轮的输入。
     */
    val prompt: String

    /**
     * 垫在对话历史前面的一段现状（和记忆、摘要放一起，DESIGN.md §6.9），比如记账的类别表：
     * 模型每句话都该知道、会变但不常变的东西。变一次前缀缓存失效一次，所以别放每分钟都在变的。没有就是 null
     */
    suspend fun background(context: Context): String? = null

    /** 对话 agent 的工具（桌面速记同一套）。写操作（[com.abc.daodian.agent.engine.tool.ToolEffect.WRITE]）会在对话里留痕 */
    fun tools(context: Context): List<Tool>

    /** 它的写操作留什么痕。不是它的工具就返回 null */
    fun trace(call: ToolTrace): TraceView? = null

    /** 对话空状态的例句，点一下直接发出去 */
    val examples: List<String> get() = emptyList()

    /** 连不上模型时「手动填一条」去哪（路由）。没有就不给这个按钮 */
    val manualEntry: String? get() = null

    /** app 自己发起的一轮（比如每晚对账）。[key] 是 `ledger:check` 冒号后面那段；不认识就是 null */
    suspend fun trigger(context: Context, key: String): Trigger? = null

    /**
     * 问过你、还等着你点的那一轮（比如通知弹了、还没对的每晚对账）。对话末尾画成一段虚线，
     * 不用非得从通知进。对完了、账都认出来了就变回 null，所以是一条流
     */
    fun pendingTrigger(context: Context): Flow<PendingTrigger?> = flowOf(null)

    /** 虚线段上点了「今天算了」。[key] 同 [trigger] */
    suspend fun dismissTrigger(context: Context, key: String) {}

    /**
     * 问卡上 [ref]（「#12」）那一题，你顺手办了什么（记账：挂上的标签）。「就这样」时问一次，
     * 收起后接在答案后面、写进回给模型的话。不是自己的 ref、什么都没办就是 null。
     * 画那一块的是 [com.abc.daodian.agent.shell.FeatureUi.AskAddon]
     */
    suspend fun askNote(context: Context, ref: String): AskNote? = null

    /** 体检项：挂了会让这个模块的核心功能失效的系统权限 */
    fun health(context: Context): List<HealthItem> = emptyList()

    /** 冷启动（包括被闹钟、广播拉起的进程）要做的事。在主线程上调，耗时的自己开协程 */
    fun onAppStart(context: Context) {}
}

/** app 自己发起的一轮 */
sealed interface Trigger {
    /** 交给 agent 开一轮：这是开场白（用户没说过，画成分隔线） */
    data class Turn(val text: String) : Trigger

    /** 不用麻烦模型，直接回一句（比如「账都对上了」） */
    data class Note(val text: String) : Trigger
}

/** 问过你、还等着你点的一轮（[Feature.pendingTrigger]） */
data class PendingTrigger(
    /** 完整的键，`ledger:check`。点了交给 [FeatureRegistry.trigger] */
    val key: String,
    /** 虚线上的字。和那一轮开场白冒号前那段一样（「每晚对账」），点了之后原地换成的实线上也是它 */
    val label: String,
    /** 什么时候问的，虚线上写成「21:30」 */
    val at: Long,
    /** 虚线底下那句：「有 3 笔账没认出来，现在对一下？」 */
    val text: String,
    /** 主按钮：「现在对」 */
    val action: String
)

/**
 * 体检的一项。[ok] = null 是查不到、只能手动设的（MagicOS 的「应用启动管理」）。
 * [fixIntent] 是「去开」跳到哪儿。
 */
data class HealthItem(
    val label: String,
    val ok: Boolean?,
    val detail: String,
    val fixIntent: Intent?
)

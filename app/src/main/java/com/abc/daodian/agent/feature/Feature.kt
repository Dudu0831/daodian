package com.abc.daodian.agent.feature

import android.content.Context
import android.content.Intent
import com.abc.daodian.agent.engine.tool.Tool

/**
 * 一个模块接到 agent 上的唯一接头。agent 只认识它，不知道提醒、账是什么。见 PROJECT_STRUCTURE.md「接头」
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

    /**
     * 接在基础提示词后面的一段，按 `Features.kt` 的顺序拼。必须是常量 ——
     * system 要逐字节稳定（前缀缓存），会变的东西放进那一轮的输入。
     */
    val prompt: String

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

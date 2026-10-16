package com.abc.daodian.assistant.plugin

import android.content.Context
import androidx.compose.runtime.Composable
import com.abc.daodian.runtime.ask.AskNote
import com.abc.daodian.runtime.tool.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 一个模块给助手的那一份（模块接头 `module/Module` 的 `assistant`）。助手只认识它，不知道提醒、账是什么。
 * 见 DESIGN.md §2.2「接头」
 *
 * 给的是能力和数据：工具、垫在历史前面的现状、痕、例句、app 自己发起的一轮、问卡上挂的一块。
 * **不给提示词**：怎么用这些工具，助手在自己的提示词里写全（`assistant/AssistantPrompt`），模块加了、改了工具要回去跟着改。
 * 页面、抽屉卡、设置行、体检、诊断给的是壳（`shell/entry/ShellEntry`），冷启动要做的事在模块接头上。
 */
interface AssistantPlugin {

    /** 和模块的 id 一样（「ledger」）：trigger 键的前缀。装的时候根目录查一遍 */
    val id: String

    /**
     * 垫在对话历史前面的一段现状（和记忆、摘要放一起，DESIGN.md §6.9），比如记账的类别表：
     * 模型每句话都该知道、会变但不常变的东西。变一次前缀缓存失效一次，所以别放每分钟都在变的。没有就是 null
     */
    suspend fun background(context: Context): String? = null

    /** 助手的工具（桌面速记同一套）。写操作（[com.abc.daodian.runtime.tool.ToolEffect.WRITE]）会在对话里留痕 */
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
     * 收起后接在答案后面、写进回给模型的话。不是自己的 ref、什么都没办就是 null。画那一块的是 [AskAddon]
     */
    suspend fun askNote(context: Context, ref: String): AskNote? = null

    /**
     * 问卡上一题底下挂的一块（记账：顺手打标签）。[ref] 是模型在那题上写的「#12」，不是自己的就什么都不画。
     * 只在等你答的时候画；办了什么，「就这样」时由 [askNote] 报给问卡
     */
    @Composable
    fun AskAddon(ref: String) {}
}

/** app 自己发起的一轮 */
sealed interface Trigger {
    /** 交给助手开一轮：这是开场白（用户没说过，画成分隔线） */
    data class Turn(val text: String) : Trigger

    /** 不用麻烦模型，直接回一句（比如「账都对上了」） */
    data class Note(val text: String) : Trigger
}

/** 问过你、还等着你点的一轮（[AssistantPlugin.pendingTrigger]） */
data class PendingTrigger(
    /** 完整的键，`ledger:check`。点了交给 [AssistantPlugins.trigger] */
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

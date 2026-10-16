package com.abc.daodian.runtime

import com.abc.daodian.runtime.context.ContextPolicy
import com.abc.daodian.runtime.context.LastTurns
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.StopReason
import com.abc.daodian.runtime.engine.Turn
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolOutcome
import java.time.ZonedDateTime

/**
 * 花名册上的一行：一个 agent 是谁、归谁、什么时候醒、说哪几份提示词。系统里一共有哪些，看根目录 `Agents.kt`。
 * 实现它的是各 agent 自己的 object（`AssistantAgent`、`OrganizerAgent`……），它们再用 `build(...)` 现建一个 [Agent] 去跑。
 */
interface AgentInfo {
    /** 「organizer」：trace 文件、诊断都按它认 */
    val id: String

    /** 「记账整理员」 */
    val label: String

    /** 归哪一块：「ledger」 */
    val owner: String

    /** 什么时候醒，给人看：「后台：每 3 小时、对账前、手动点」 */
    val wakes: String

    /** 它跑的时候可能用的提示词（整理员开不开打标签是两份）。[Agent.prompt] 必须是其中一份 */
    val prompts: List<Prompt>

    /** 能不能出问卡。只有助手能；问不了人的手上不该有 `ask_user` */
    val canAsk: Boolean get() = false
}

/**
 * 一份 system。[text] 必须逐字节稳定（前缀缓存），会变的东西放进那一轮的输入。
 *
 * @param version 改了措辞就加一。写进 trace、导出诊断，有记录列的也写（流水的 parsed_by、tidy_runs）
 * @param tools 这份提示词是照着哪几个工具写的。跑之前和手上真有的工具对一遍（[AgentRuntime]），
 *   模块加了、删了、改了名工具，提示词不会知道 —— 靠这一下当场发现
 */
class Prompt(val text: String, val version: String, val tools: Set<String>)

/** 跑一次要的全部东西。工具要连库，由各 agent 的 `build(...)` 现建 */
data class Agent(
    val info: AgentInfo,
    val prompt: Prompt,
    val tools: List<Tool>,
    /** 每步喂哪些轮。后台的一次性 agent 只有当前这一轮 */
    val context: ContextPolicy = LastTurns(1),
    /** 一轮最多调几次模型（调工具 → 拿结果 → 再调算一次） */
    val maxSteps: Int = DEFAULT_MAX_STEPS
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 6
    }
}

/**
 * debug 包（[AgentRuntime.install] 的 strict）跑之前查出来对不上：不在花名册上、提示词不是它自己的、
 * 手上的工具和提示词写的不一样、问不了人却带着 `ask_user`。消息里写着去哪改
 */
class AgentMisconfigured(message: String) : IllegalStateException(message)

/**
 * 这一轮的开头。[trigger] = app 自己发起的（每晚对账），不是用户说的；[images] 是随这句话发的图片，
 * 见 [Item.UserMessage]
 */
data class Input(
    val text: String,
    val at: ZonedDateTime,
    val trigger: Boolean = false,
    val images: List<String> = emptyList()
)

/** 后台 agent 跑完一次的结果（[AgentRuntime.runOnce]） */
sealed interface RunResult {

    /** 这一轮每个工具办的结果，按先后 */
    val outcomes: List<ToolOutcome>

    /** 还没配置模型，没打网络 */
    data object NotConfigured : RunResult {
        override val outcomes: List<ToolOutcome> = emptyList()
    }

    data class Done(val turn: Turn, val stop: StopReason, override val outcomes: List<ToolOutcome>) : RunResult

    data class Failed(
        val turn: Turn,
        val reason: String,
        val cause: Throwable?,
        override val outcomes: List<ToolOutcome>
    ) : RunResult
}

/** 模型最后说的那句话（没说话、没跑完是 null） */
val RunResult.reply: String?
    get() = (this as? RunResult.Done)?.turn?.items?.filterIsInstance<Item.AssistantMessage>()?.lastOrNull()?.text?.trim()

/** 工具交上来的结构化结果里，是 [T] 的那些（[ToolOutcome.payload]，各工具自己说明是什么类型） */
inline fun <reified T> RunResult.payloads(): List<T> = outcomes.mapNotNull { it.payload as? T }

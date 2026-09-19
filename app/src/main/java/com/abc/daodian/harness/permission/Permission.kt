package com.abc.daodian.harness.permission

import com.abc.daodian.harness.Item
import com.abc.daodian.harness.tool.Tool
import com.abc.daodian.harness.tool.ToolEffect

/** 授权模式 */
enum class PermissionMode {
    /** 默认：会改东西的工具（[ToolEffect.WRITE]）执行前先问用户，只读的直接跑 */
    ASK,

    /** 放开：什么都直接跑 */
    AUTO
}

/** 用户对一次写操作的答复 */
sealed interface Approval {
    data object Approved : Approval

    data object Denied : Approval

    /**
     * 没同意，而且直接说了要怎么改（在输入框里打字发出去）。这一轮就此收住，
     * [instead] 由界面作为下一句话重新发 —— 模型在新的一轮里带着上下文重办。
     */
    data class Redirected(val instead: String) : Approval
}

/** 向用户要授权。界面实现它：亮出授权条、等用户答复。挂起多久都行 */
fun interface Approver {
    suspend fun approve(call: Item.ToolCall, tool: Tool): Approval
}

class PermissionGate(private val mode: PermissionMode, private val approver: Approver) {

    suspend fun decide(call: Item.ToolCall, tool: Tool): Approval = when {
        tool.effect == ToolEffect.READ -> Approval.Approved
        mode == PermissionMode.AUTO -> Approval.Approved
        else -> approver.approve(call, tool)
    }

    fun needsApproval(tool: Tool): Boolean = tool.effect == ToolEffect.WRITE && mode == PermissionMode.ASK
}

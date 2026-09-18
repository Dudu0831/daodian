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

/** 向用户要授权。界面实现它：弹卡片、等用户点，返回同不同意。挂起多久都行 */
fun interface Approver {
    suspend fun approve(call: Item.ToolCall, tool: Tool): Boolean
}

class PermissionGate(private val mode: PermissionMode, private val approver: Approver) {

    /** 返回 false = 用户不同意，工具不执行 */
    suspend fun allows(call: Item.ToolCall, tool: Tool): Boolean = when {
        tool.effect == ToolEffect.READ -> true
        mode == PermissionMode.AUTO -> true
        else -> approver.approve(call, tool)
    }

    fun needsApproval(tool: Tool): Boolean = tool.effect == ToolEffect.WRITE && mode == PermissionMode.ASK
}

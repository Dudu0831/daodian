package com.abc.daodian.assistant.skill

import android.content.Context
import com.abc.daodian.assistant.conversation.data.ConversationStore

/**
 * 对话记录里查一次工具调用的原样参数。给模块用的（插口包里，模块可以 import）：
 * 提醒编辑页「原话」下面那行「依据」，是建这条提醒的那次 `create_reminder` 的 `basis`（DESIGN.md §6.4）。
 * 模块不碰对话库本身。
 */
object CallHistory {

    /** [tool] 是工具名，[ref] 是那次办成后指向的记录（新提醒的 id）。查不到是 null */
    suspend fun arguments(context: Context, tool: String, ref: Long): String? =
        ConversationStore.get(context.applicationContext).callArguments(tool, ref)
}

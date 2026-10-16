package com.abc.daodian.assistant.skill

import android.content.Context
import com.abc.daodian.runtime.ask.AskNote
import com.abc.daodian.runtime.tool.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * 各模块给助手的那几份（[AssistantSkill]），按根目录 `Modules.kt` 的顺序。`DaodianApp.onCreate` 装进来，助手只读它。
 * Worker、Receiver 都在 Application 之后跑，拿得到。
 */
object AssistantSkills {

    @Volatile
    var skills: List<AssistantSkill> = emptyList()
        private set

    fun install(list: List<AssistantSkill>) {
        require(list.map { it.id }.distinct().size == list.size) { "模块 id 重复：${list.map { it.id }}" }
        skills = list
    }

    /** 各模块的工具，按模块顺序（顺序变了 = 发给模型的工具列表变了，前缀缓存失效一次） */
    fun tools(context: Context): List<Tool> = skills.flatMap { it.tools(context.applicationContext) }

    fun trace(call: ToolTrace): TraceView? = skills.firstNotNullOfOrNull { it.trace(call) }

    val examples: List<String> get() = skills.flatMap { it.examples }

    val manualEntry: String? get() = skills.firstNotNullOfOrNull { it.manualEntry }

    /** 各模块垫在历史前面的现状，按模块顺序。哪个模块读库出错就少它一段，不连累这句话 */
    suspend fun backgrounds(context: Context): List<String> =
        skills.mapNotNull { s ->
            val text = try {
                s.background(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            text?.takeIf { it.isNotBlank() }
        }

    /** 问卡上一题顺手办了的事，第一个认得 [ref] 的模块说了算。读库出错就当没有，不耽误交卷 */
    suspend fun askNote(context: Context, ref: String): AskNote? =
        skills.firstNotNullOfOrNull { s ->
            try {
                s.askNote(context, ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /** [key] 形如 `ledger:check`：冒号前是模块 id */
    suspend fun trigger(context: Context, key: String): Trigger? {
        val id = key.substringBefore(':')
        return skills.firstOrNull { it.id == id }?.trigger(context, key.substringAfter(':', ""))
    }

    /** 等你点的那一轮。几个模块同时有的话只给第一个：对完一个，下一个自己冒出来 */
    fun pendingTrigger(context: Context): Flow<PendingTrigger?> {
        val flows = skills.map { it.pendingTrigger(context) }
        if (flows.isEmpty()) return flowOf(null)
        return combine(flows) { all -> all.firstOrNull { it != null } }
    }

    suspend fun dismissTrigger(context: Context, key: String) {
        val id = key.substringBefore(':')
        skills.firstOrNull { it.id == id }?.dismissTrigger(context, key.substringAfter(':', ""))
    }
}

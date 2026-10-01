package com.abc.daodian.agent.feature

import android.content.Context
import com.abc.daodian.agent.engine.ask.AskNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * 装了哪些模块。`DaodianApp.onCreate` 把根目录 `Features.kt` 的清单装进来，agent 只读它。
 * Worker、Receiver 都在 Application 之后跑，拿得到。
 */
object FeatureRegistry {

    @Volatile
    var features: List<Feature> = emptyList()
        private set

    fun install(list: List<Feature>) {
        require(list.map { it.id }.distinct().size == list.size) { "模块 id 重复：${list.map { it.id }}" }
        features = list
    }

    fun trace(call: ToolTrace): TraceView? = features.firstNotNullOfOrNull { it.trace(call) }

    val examples: List<String> get() = features.flatMap { it.examples }

    val manualEntry: String? get() = features.firstNotNullOfOrNull { it.manualEntry }

    fun health(context: Context): List<HealthItem> = features.flatMap { it.health(context) }

    /** 各模块垫在历史前面的现状，按模块顺序。哪个模块读库出错就少它一段，不连累这句话 */
    suspend fun backgrounds(context: Context): List<String> =
        features.mapNotNull { f ->
            val text = try {
                f.background(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            text?.takeIf { it.isNotBlank() }
        }

    /** 问卡上一题顺手办了的事，第一个认得 [ref] 的模块说了算。读库出错就当没有，不耽误交卷 */
    suspend fun askNote(context: Context, ref: String): AskNote? =
        features.firstNotNullOfOrNull { f ->
            try {
                f.askNote(context, ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /** 各模块的诊断段（模块名 to 那一段）。哪个模块读库出错就把错写进去，不连累别的 */
    suspend fun diagnostics(context: Context): List<Pair<String, String>> =
        features.mapNotNull { f ->
            val text = try {
                f.diagnostics(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "读不出来：${e.javaClass.simpleName} ${e.message}"
            }
            text?.takeIf { it.isNotBlank() }?.let { f.label to it }
        }

    /** [key] 形如 `ledger:check`：冒号前是模块 id */
    suspend fun trigger(context: Context, key: String): Trigger? {
        val id = key.substringBefore(':')
        return features.firstOrNull { it.id == id }?.trigger(context, key.substringAfter(':', ""))
    }

    /** 等你点的那一轮。几个模块同时有的话只给第一个：对完一个，下一个自己冒出来 */
    fun pendingTrigger(context: Context): Flow<PendingTrigger?> {
        val flows = features.map { it.pendingTrigger(context) }
        if (flows.isEmpty()) return flowOf(null)
        return combine(flows) { all -> all.firstOrNull { it != null } }
    }

    suspend fun dismissTrigger(context: Context, key: String) {
        val id = key.substringBefore(':')
        features.firstOrNull { it.id == id }?.dismissTrigger(context, key.substringAfter(':', ""))
    }
}

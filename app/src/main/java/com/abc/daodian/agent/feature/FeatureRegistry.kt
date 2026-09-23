package com.abc.daodian.agent.feature

import android.content.Context

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

    /** [key] 形如 `ledger:check`：冒号前是模块 id */
    suspend fun trigger(context: Context, key: String): Trigger? {
        val id = key.substringBefore(':')
        return features.firstOrNull { it.id == id }?.trigger(context, key.substringAfter(':', ""))
    }
}

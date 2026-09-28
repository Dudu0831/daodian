package com.abc.daodian.agent.memory

import android.content.Context
import com.abc.daodian.agent.engine.context.Background
import com.abc.daodian.agent.engine.context.Preamble
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.agent.memory.tidy.Digest
import com.abc.daodian.agent.memory.tidy.TidyLog
import java.time.Instant
import java.time.ZoneId

/**
 * 每一步请求前垫在历史前面的那段：记下的事 + 各模块的现状（记账的类别表）+ 更早对话的摘要。见 DESIGN.md §6.9
 *
 * 这几样一般只在后台整理完、新建了类别之后才变，两次变动之间这段一个字不变，前缀缓存吃得上。
 */
object Recall {

    /** 对话页：记忆 + 模块现状 + 摘要；摘要盖住的轮次不再原样喂 */
    fun chat(context: Context): Background {
        val app = context.applicationContext
        return Background { session ->
            val last = session.turns.lastOrNull()?.id ?: 0
            // 摘要盖到的轮次比现在还靠后：对话被清过，那段摘要说的是别的对话了
            val digest = TidyLog.get(app).digest()?.takeIf { it.through < last }
            preamble(MemoryBook.get(app).all(), digest, FeatureRegistry.backgrounds(app))
        }
    }

    /** 桌面速记：一次性的对话，没有摘要，垫记忆和模块现状 —— 「老地方」「晚点」照样听得懂，归类照样认得类别 */
    fun quick(context: Context): Background {
        val app = context.applicationContext
        return Background { preamble(MemoryBook.get(app).all(), null, FeatureRegistry.backgrounds(app)) }
    }

    /**
     * 摘要放最后：它后面紧接着就是原样喂的轮次。三样都没有就不垫
     *
     * @param modules 各模块的现状（[com.abc.daodian.agent.feature.Feature.background]），自带标题
     */
    fun preamble(
        memories: List<Memory>,
        digest: Digest?,
        modules: List<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Preamble? {
        if (memories.isEmpty() && digest == null && modules.isEmpty()) return null
        val parts = buildList {
            if (memories.isNotEmpty()) {
                add(buildString {
                    append("你记得的关于他的事（m 编号 · 记下或改过的日子）：\n")
                    memories.forEach { append("- m${it.id} · ${day(it.updatedAt, zone)} · ${it.text}\n") }
                }.trimEnd())
            }
            modules.forEach { add(it.trim()) }
            if (digest != null) {
                add("更早的对话，摘要（到 ${day(digest.throughAt, zone)} 为止，之后的对话原样在下面）：\n" + digest.text.trim())
            }
        }
        return Preamble(parts.joinToString("\n\n"), digest?.through ?: 0)
    }

    private fun day(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).let { "${it.monthValue}月${it.dayOfMonth}日" }
}

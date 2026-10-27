package com.abc.daodian.assistant.memory

import android.content.Context
import com.abc.daodian.assistant.memory.tidy.TidyLog
import com.abc.daodian.assistant.plugin.AssistantPlugins
import com.abc.daodian.runtime.context.Background
import com.abc.daodian.runtime.memory.MemoryPreamble

/**
 * 每一步请求前垫在历史前面的那段：记下的事 + 各模块的现状（记账的类别表）+ 更早对话的摘要。见 DESIGN.md §6.9
 *
 * 这几样一般只在后台整理完、新建了类别之后才变，两次变动之间这段一个字不变，前缀缓存吃得上。
 * 怎么拼成一段话在 runtime 的 [MemoryPreamble]（后台的几个 agent 只垫记忆，也用它）；这里只管对话页、速记各垫哪几样。
 */
object Recall {

    /** 对话页：记忆 + 模块现状 + 摘要；摘要盖住的轮次不再原样喂 */
    fun conversation(context: Context): Background {
        val app = context.applicationContext
        return Background { session ->
            val last = session.turns.lastOrNull()?.id ?: 0
            // 摘要盖到的轮次比现在还靠后：对话被清过，那段摘要说的是别的对话了
            val digest = TidyLog.get(app).digest()?.takeIf { it.through < last }
            MemoryPreamble.of(MemoryBook.get(app).all(), digest, AssistantPlugins.backgrounds(app))
        }
    }

    /** 桌面速记：一次性的对话，没有摘要，垫记忆和模块现状 —— 「老地方」「晚点」照样听得懂，归类照样认得类别 */
    fun quick(context: Context): Background {
        val app = context.applicationContext
        return Background { MemoryPreamble.of(MemoryBook.get(app).all(), null, AssistantPlugins.backgrounds(app)) }
    }
}

package com.abc.daodian.agent.memory

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.abc.daodian.agent.conversation.data.ChatStore
import com.abc.daodian.agent.memory.tidy.TidyLog
import com.abc.daodian.agent.memory.tidy.TidyWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.memoryDataStore by preferencesDataStore("memory")

/**
 * 记忆的设置：聊完要不要自己整理（记忆页最底下那个开关）。见 DESIGN.md §6.9
 *
 * 关掉只停「自己从对话里找值得记的」：你明说「记住」照记，对话攒够了照样压成摘要（那是为了上下文，不是记忆）。
 */
object MemorySettings {

    private val AUTO_TIDY = booleanPreferencesKey("auto_tidy")

    fun autoTidyFlow(context: Context): Flow<Boolean> =
        context.applicationContext.memoryDataStore.data.map { it[AUTO_TIDY] ?: true }

    suspend fun autoTidy(context: Context): Boolean = autoTidyFlow(context).first()

    /**
     * 关掉时把排着的那次整理取消。再打开时从现在接着看 —— 关着那段时间聊的不补着整理：
     * 你关它，多半就是不想让那几句被记下
     */
    suspend fun setAutoTidy(context: Context, on: Boolean) {
        val app = context.applicationContext
        app.memoryDataStore.edit { it[AUTO_TIDY] = on }
        if (on) TidyLog.get(app).skipTo(ChatStore.get(app).lastTurnId())
        else TidyWorker.cancel(app)
    }
}

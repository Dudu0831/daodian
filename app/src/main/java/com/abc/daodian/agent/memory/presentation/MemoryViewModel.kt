package com.abc.daodian.agent.memory.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.agent.memory.Memory
import com.abc.daodian.agent.memory.MemoryBook
import com.abc.daodian.agent.memory.MemoryEdit
import com.abc.daodian.agent.memory.MemoryRules
import com.abc.daodian.agent.memory.MemorySettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 记忆管理页（含「聊完自己整理」开关）和设置首页「记忆」那一行的状态。见 DESIGN.md §6.9
 *
 * 只是管理：看、改、删、自己写一条、开关自动整理。记忆是后台的事，这里没有「确认」「不对」这一类。
 * 写进去的和模型记的走同一道规矩（[MemoryRules]），不合规的原因当场写在底纸上。
 */
class MemoryViewModel(app: Application) : AndroidViewModel(app) {

    private val book = MemoryBook.get(app)

    /** null = 还在读 */
    val memories: StateFlow<List<Memory>?> = book.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val autoTidy: StateFlow<Boolean> = MemorySettings.autoTidyFlow(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setAutoTidy(on: Boolean) = viewModelScope.launch { MemorySettings.setAutoTidy(getApplication(), on) }

    /**
     * 存一条：[id] 为 null 是新记。不合规返回原因（界面写在输入框底下），存好了返回 null。
     * 你自己写的记成 [Memory.USER]；改模型记的那条，来路不变
     */
    suspend fun save(id: Long?, text: String): String? {
        val t = text.trim().replace(Regex("\\s*\\n\\s*"), "，")
        if (t.isEmpty()) return "写点什么再存"
        val edit = if (id == null) MemoryEdit(add = listOf(t)) else MemoryEdit(update = mapOf(id to t))
        MemoryRules.problemOf(edit, book.all())?.let { return it }
        book.apply(edit, Memory.USER)
        return null
    }

    fun delete(m: Memory) = viewModelScope.launch { book.apply(MemoryEdit(remove = setOf(m.id)), Memory.USER) }

    /** 撤销删除：原样放回去 */
    fun restore(m: Memory) = viewModelScope.launch { book.restore(m) }
}

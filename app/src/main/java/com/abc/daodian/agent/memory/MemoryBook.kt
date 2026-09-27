package com.abc.daodian.agent.memory

import android.content.Context
import com.abc.daodian.agent.conversation.data.ChatDatabase
import com.abc.daodian.agent.memory.data.MemoryDao
import com.abc.daodian.agent.memory.data.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart

/**
 * 记忆的存取（`chat.db` 的 `memories` 表）。每一步请求前都要读一遍（[Recall]），所以内存里留一份，
 * 只经这里写、写完重读。见 DESIGN.md §6.9
 */
class MemoryBook private constructor(private val dao: MemoryDao) : MemoryBackend {

    private val cache = MutableStateFlow<List<Memory>?>(null)

    /** 记忆页用：一变就推 */
    val flow: Flow<List<Memory>> = cache.onStart { if (cache.value == null) reload() }.filterNotNull()

    override suspend fun all(): List<Memory> = cache.value ?: reload()

    override suspend fun apply(edit: MemoryEdit, source: String): MemoryApplied {
        val now = System.currentTimeMillis()
        val (added, updated, removed) = dao.edit(
            add = edit.add.map { MemoryEntity(text = it, createdAt = now, updatedAt = now, source = source) },
            update = edit.update,
            remove = edit.remove,
            now = now
        )
        reload()
        return MemoryApplied(added.map(::toMemory), updated.map(::toMemory), removed.map(::toMemory))
    }

    /** 撤销删除：原样放回去，编号和日子都不变 */
    suspend fun restore(m: Memory) {
        dao.insertMemory(MemoryEntity(id = m.id, text = m.text, createdAt = m.createdAt, updatedAt = m.updatedAt, source = m.source))
        reload()
    }

    private suspend fun reload(): List<Memory> = dao.memories().map(::toMemory).also { cache.value = it }

    private fun toMemory(e: MemoryEntity) = Memory(e.id, e.text, e.updatedAt, e.source, e.createdAt)

    companion object {
        @Volatile private var instance: MemoryBook? = null

        fun get(context: Context): MemoryBook =
            instance ?: synchronized(this) {
                instance ?: MemoryBook(ChatDatabase.get(context).memoryDao()).also { instance = it }
            }
    }
}

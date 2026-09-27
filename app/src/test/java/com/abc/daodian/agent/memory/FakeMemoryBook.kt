package com.abc.daodian.agent.memory

/** 测试用的记忆本，全在内存里。[texts] 依次是 m1、m2… */
class FakeMemoryBook(vararg texts: String) : MemoryBackend {
    val list = texts.mapIndexed { i, t -> Memory(i + 1L, t, 0, Memory.TIDY) }.toMutableList()
    private var nextId = texts.size + 1L

    override suspend fun all(): List<Memory> = list.toList()

    override suspend fun apply(edit: MemoryEdit, source: String): MemoryApplied {
        val removed = list.filter { it.id in edit.remove }
        list.removeAll(removed)
        val updated = edit.update.mapNotNull { (id, text) ->
            val i = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@mapNotNull null
            list[i].copy(text = text).also { list[i] = it }
        }
        val added = edit.add.map { Memory(nextId++, it, 0, source).also(list::add) }
        return MemoryApplied(added, updated, removed)
    }
}

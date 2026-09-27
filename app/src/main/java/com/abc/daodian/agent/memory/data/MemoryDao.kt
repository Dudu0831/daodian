package com.abc.daodian.agent.memory.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/*
 * 记忆和整理记录，和对话同在 `chat.db`（DESIGN.md §6.9）。出了岔子最坏丢的是记忆，连累不到闹钟。
 */

/** 记下的一件事，见 [com.abc.daodian.agent.memory.Memory] */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** said / tidy / user */
    val source: String
)

/**
 * 后台整理跑一次记一行，成没成都记 —— 这台机器看不到 logcat，出了问题靠这张表。
 * 也是整理的进度：最近一次成功的 [memoryThrough] 是记忆整理到哪一轮了，最近一次写了摘要的那行就是现在的摘要。
 */
@Entity(tableName = "tidy_runs")
data class TidyRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 谁叫的：turn（攒够了要压）/ idle（闲下来） */
    val reason: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val model: String = "",
    /** 这次看的是哪几轮（含两头） */
    val fromTurnId: Long = 0,
    val toTurnId: Long = 0,
    /** 成了之后，记忆整理到哪一轮（含）。没成是 null */
    val memoryThrough: Long? = null,
    /** 这次写了摘要：摘要盖住到哪一轮（含）、那一轮是什么时候说的，和摘要本身 */
    val digestThrough: Long? = null,
    val digestThroughAt: Long? = null,
    val digest: String? = null,
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val error: String? = null
)

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories ORDER BY id")
    suspend fun memories(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id IN (:ids)")
    suspend fun memoriesByIds(ids: Collection<Long>): List<MemoryEntity>

    @Insert
    suspend fun insertMemory(m: MemoryEntity): Long

    @Update
    suspend fun updateMemory(m: MemoryEntity)

    @Query("DELETE FROM memories WHERE id IN (:ids)")
    suspend fun deleteMemories(ids: Collection<Long>)

    /** 一次改动整个落或整个不落 */
    @Transaction
    suspend fun edit(add: List<MemoryEntity>, update: Map<Long, String>, remove: Set<Long>, now: Long): Triple<List<MemoryEntity>, List<MemoryEntity>, List<MemoryEntity>> {
        val existing = memoriesByIds(update.keys + remove).associateBy { it.id }
        val removed = remove.mapNotNull { existing[it] }
        if (removed.isNotEmpty()) deleteMemories(removed.map { it.id })
        val updated = update.mapNotNull { (id, text) ->
            existing[id]?.takeIf { id !in remove }?.copy(text = text, updatedAt = now)?.also { updateMemory(it) }
        }
        val added = add.map { it.copy(id = insertMemory(it)) }
        return Triple(added, updated, removed)
    }

    @Insert
    suspend fun insertRun(run: TidyRunEntity): Long

    @Update
    suspend fun updateRun(run: TidyRunEntity)

    @Query("SELECT * FROM tidy_runs WHERE memoryThrough IS NOT NULL ORDER BY id DESC LIMIT 1")
    suspend fun lastSuccess(): TidyRunEntity?

    @Query("SELECT * FROM tidy_runs WHERE digest IS NOT NULL ORDER BY id DESC LIMIT 1")
    suspend fun lastDigest(): TidyRunEntity?

    @Query("SELECT * FROM tidy_runs ORDER BY id DESC LIMIT 1")
    suspend fun lastRun(): TidyRunEntity?
}

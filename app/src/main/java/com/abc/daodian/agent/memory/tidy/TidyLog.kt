package com.abc.daodian.agent.memory.tidy

import android.content.Context
import com.abc.daodian.agent.conversation.data.ChatDatabase
import com.abc.daodian.agent.memory.data.MemoryDao
import com.abc.daodian.agent.memory.data.TidyRunEntity

/** 更早那段对话的摘要：盖住到第 [through] 轮（含），那一轮是 [throughAt] 说的 */
data class Digest(val text: String, val through: Long, val throughAt: Long)

/**
 * 整理记录（`tidy_runs`）的读写，和从里面读出来的进度：记忆整理到哪一轮、现在的摘要。
 * 摘要每一步请求前都要读（[com.abc.daodian.agent.memory.Recall]），内存里留一份，整理完换掉。
 */
class TidyLog private constructor(private val dao: MemoryDao) {

    /** [loaded] 之后才作数：null 是真没有摘要 */
    @Volatile private var cached: Digest? = null
    @Volatile private var loaded = false

    suspend fun digest(): Digest? {
        if (!loaded) {
            cached = dao.lastDigest()?.let { r -> r.digest?.let { Digest(it, r.digestThrough ?: 0, r.digestThroughAt ?: r.startedAt) } }
            loaded = true
        }
        return cached
    }

    /** 记忆整理到哪一轮了（含）。对话被清过、轮次号比它还小的，从头算 */
    suspend fun memoryThrough(lastTurnId: Long): Long =
        (dao.lastSuccess()?.memoryThrough ?: 0).takeIf { it <= lastTurnId } ?: 0

    suspend fun lastRun(): TidyRunEntity? = dao.lastRun()

    /** 记忆进度直接跳到第 [turnId] 轮：之前的不整理了（重新打开自动整理时，见 MemorySettings） */
    suspend fun skipTo(turnId: Long) {
        val now = System.currentTimeMillis()
        dao.insertRun(TidyRunEntity(reason = "resume", startedAt = now, finishedAt = now, toTurnId = turnId, memoryThrough = turnId))
    }

    suspend fun start(run: TidyRunEntity): TidyRunEntity = run.copy(id = dao.insertRun(run))

    suspend fun finish(run: TidyRunEntity) {
        dao.updateRun(run)
        if (run.digest != null) {
            cached = Digest(run.digest, run.digestThrough ?: 0, run.digestThroughAt ?: run.startedAt)
            loaded = true
        }
    }

    companion object {
        @Volatile private var instance: TidyLog? = null

        fun get(context: Context): TidyLog =
            instance ?: synchronized(this) {
                instance ?: TidyLog(ChatDatabase.get(context).memoryDao()).also { instance = it }
            }
    }
}

package com.abc.daodian.agent.conversation.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * 对话记录，**单独一个库**（`chat.db`），和提醒的 `reminder.db` 互不牵连。见 DESIGN.md §6.8
 *
 * 为什么不并进提醒的 `reminder.db`：那个库是「唯一不允许出错」的部分，
 * 对话表以后要改结构、迁移出了岔子，最坏也只是丢聊天记录，连累不到闹钟。
 * 同理，这个库改表时可以比那边大胆 —— 但照样要写迁移，别 destructive 掉用户的对话。
 */
@Database(entities = [ChatItemEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "chat.db"
                ).build().also { instance = it }
            }
    }
}

/**
 * 对话里的一项，对应 `harness.Item` 的四种之一，由 [kind] 区分。一轮按 [seq] 排。
 * 各字段只有对应的那种才有值，映射见 [ChatStore]。
 */
@Entity(tableName = "chat_items", indices = [Index("turnId")])
data class ChatItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val turnId: Long,
    val seq: Int,
    /** USER / ASSISTANT / TOOL_CALL / TOOL_RESULT */
    val kind: String,
    /** 用户原话 / 回复正文 / 工具参数 JSON / 回给模型的结果 */
    val text: String,
    /** USER：说这句话的时刻（毫秒）和当时所在时区。重放时靠它还原「明天」指哪天 */
    val atMillis: Long? = null,
    val zoneId: String? = null,
    /** TOOL_CALL / TOOL_RESULT */
    val callId: String? = null,
    /** TOOL_CALL */
    val toolName: String? = null,
    /** TOOL_RESULT */
    val ok: Boolean? = null,
    val ref: Long? = null
)

@Dao
interface ChatDao {

    /** 最近 [turns] 轮，按轮、轮内顺序排好 */
    @Query(
        "SELECT * FROM chat_items WHERE turnId IN " +
            "(SELECT DISTINCT turnId FROM chat_items ORDER BY turnId DESC LIMIT :turns) " +
            "ORDER BY turnId, seq"
    )
    suspend fun recent(turns: Int): List<ChatItemEntity>

    /** 办成后指向 [ref] 的那次 [tool] 调用的参数（最近一次）。编辑页从 create_reminder 的参数里拿「依据」 */
    @Query(
        "SELECT c.text FROM chat_items c JOIN chat_items r ON r.callId = c.callId AND r.kind = 'TOOL_RESULT' " +
            "WHERE c.kind = 'TOOL_CALL' AND c.toolName = :tool AND r.ok = 1 AND r.ref = :ref ORDER BY c.id DESC LIMIT 1"
    )
    suspend fun callArgumentsFor(tool: String, ref: Long): String?

    @Query("DELETE FROM chat_items WHERE turnId = :turnId")
    suspend fun deleteTurn(turnId: Long)

    @Insert
    suspend fun insertAll(items: List<ChatItemEntity>)

    /** 整轮覆盖。一轮最多十来项，整轮重写比算增量简单，也不会写出半轮 */
    @Transaction
    suspend fun replaceTurn(turnId: Long, items: List<ChatItemEntity>) {
        deleteTurn(turnId)
        insertAll(items)
    }
}

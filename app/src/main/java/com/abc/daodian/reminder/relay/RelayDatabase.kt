package com.abc.daodian.reminder.relay

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 她发来的每一句（只记你设的那个人），和后来办成了什么。单独一个库 `relay.db` ——
 * 试验版，出岔子连累不到 `reminder.db`。
 */
@Entity(
    tableName = "relay_message",
    indices = [Index(value = ["fingerprint"], unique = true), Index("at")]
)
data class RelayMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val who: String,
    /** 去掉「[2条]」这类壳之后的原话，暗号还在 */
    val text: String,
    /** 她发这句的时刻（通知上 app 写的） */
    val at: Long,
    val receivedAt: Long,
    /** posted / active / unlock / manual / test */
    val how: String,
    /** 同一条通知实时收一次、扫通知栏又扫到，只存一次 */
    val fingerprint: String,
    /** [RelayStatus] 的名字 */
    val status: String,
    /** 建了什么 / 模型怎么说 / 为什么没办成 */
    val detail: String? = null,
    /** 建成的第一条提醒 */
    val reminderId: Long? = null
)

enum class RelayStatus {
    /** 没带暗号、不是文字：只记下，不交给模型 */
    SKIPPED,
    /** 交给模型了，还没回来 */
    WORKING,
    CREATED,
    /** 模型看了，说不是要你做的事 */
    NOT_TASK,
    FAILED
}

val RelayMessage.state: RelayStatus get() = runCatching { RelayStatus.valueOf(status) }.getOrDefault(RelayStatus.FAILED)

@Dao
interface RelayDao {
    /** 指纹重复（早就收过）返回 -1 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: RelayMessage): Long

    @Update
    suspend fun update(m: RelayMessage)

    @Query("SELECT * FROM relay_message WHERE id = :id")
    suspend fun byId(id: Long): RelayMessage?

    @Query("SELECT * FROM relay_message ORDER BY at DESC, id DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<RelayMessage>>

    @Query("DELETE FROM relay_message")
    suspend fun clear()
}

@Database(entities = [RelayMessage::class], version = 1, exportSchema = true)
abstract class RelayDatabase : RoomDatabase() {
    abstract fun dao(): RelayDao

    companion object {
        @Volatile private var instance: RelayDatabase? = null

        fun get(context: Context): RelayDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, RelayDatabase::class.java, "relay.db")
                    .build().also { instance = it }
            }
    }
}

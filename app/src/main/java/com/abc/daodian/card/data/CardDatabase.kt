package com.abc.daodian.card.data

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

/**
 * 钉在桌面上的一张卡片：哪个模块的哪件事、画成哪种模板、在桌面上是哪一块。见 DESIGN.md §8.5
 *
 * 只存「画什么」，不存画出来的字 —— 剩几天每次重画时由代码现算（`CardFace`）。
 * 它是展示面的登记，自己一个库（`card.db`），不进 `reminder.db`：这张表全丢了，提醒照响。
 */
@Entity(tableName = "cards", indices = [Index("widgetId")])
data class Card(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 哪个模块的（`CardSource.id`：「reminder」） */
    val source: String,

    /** 那个模块里的哪一件（提醒的 id）。那件被删了，卡片上写「这件事不在了」 */
    val ref: Long,

    /** 模板的名字（`CardTemplate` 的 name）。存字：以后加模板不用改表 */
    val template: String,

    /** 桌面上那一块的 appWidgetId。null = 备好了、还没放上桌面（等桌面回话，或者等他手动加一块） */
    val widgetId: Int? = null,

    /** 从哪天算起（ISO 日期）。墨条两头的那个起点；大数字用不上 */
    val startDay: String,

    val createdAt: Long
)

@Dao
interface CardDao {
    @Insert suspend fun insert(card: Card): Long

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun byId(id: Long): Card?

    @Query("SELECT * FROM cards ORDER BY createdAt ASC")
    suspend fun all(): List<Card>

    @Query("SELECT * FROM cards WHERE widgetId = :widgetId LIMIT 1")
    suspend fun byWidget(widgetId: Int): Card?

    /** 备好了还没放上桌面的，最新的那张：桌面上新加一块时先认领它 */
    @Query("SELECT * FROM cards WHERE widgetId IS NULL AND template = :template ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestUnplaced(template: String): Card?

    @Query("UPDATE cards SET widgetId = :widgetId WHERE id = :id")
    suspend fun place(id: Long, widgetId: Int)

    @Query("UPDATE cards SET source = :source, ref = :ref, startDay = :startDay WHERE id = :id")
    suspend fun retarget(id: Long, source: String, ref: Long, startDay: String)

    @Query("DELETE FROM cards WHERE widgetId = :widgetId")
    suspend fun deleteByWidget(widgetId: Int)

    /** 没放上桌面的旧卡片：同一种模板只留最新的一张等着被认领，别越攒越多 */
    @Query("DELETE FROM cards WHERE widgetId IS NULL AND template = :template")
    suspend fun clearUnplaced(template: String)
}

/** 改表照 `ReminderDatabase` 的规矩：加一条 AutoMigration，不许 destructive（桌面上已经钉着的卡片会变空） */
@Database(entities = [Card::class], version = 1, exportSchema = true)
abstract class CardDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao

    companion object {
        @Volatile private var instance: CardDatabase? = null

        fun get(context: Context): CardDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, CardDatabase::class.java, "card.db")
                    .build().also { instance = it }
            }
    }
}

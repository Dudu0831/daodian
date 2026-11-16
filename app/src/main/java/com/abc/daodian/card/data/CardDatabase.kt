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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 一张卡片：模型拼的说明（[spec]，`card/spec/CardSpec` 的 JSON）+ 卡片自己记的数（[own]）+ 在桌面上是哪一块。见 DESIGN.md §8.5
 *
 * 不存画出来的字：数每次重画时按绑定现取。它是展示面的登记，自己一个库（`card.db`），不进 `reminder.db`：全丢了提醒照响。
 * 从桌面上拿掉的卡片不删（[widgetId] 回到 null），说明还在，能再钉。
 */
@Entity(tableName = "cards", indices = [Index("widgetId")])
data class Card(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 说明里的 name（「喝水」），列表里认它 */
    val name: String,
    /** 说明里的 size（「2x2」）：决定钉到哪个桌面组件上 */
    val size: String,
    val spec: String,
    val own: String = "{}",
    /** 桌面上那一块的 appWidgetId。null = 还没放上去 */
    val widgetId: Int? = null,
    /** 最近一次请桌面加它是什么时候。桌面没回话时，新加的那一块认领请得最晚的这张 */
    val pinAskedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
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

    /** 桌面上新加了一块 [size] 的、没回话：先认请桌面加过的里最晚的，没有就认最新画的那张没放的 */
    @Query(
        "SELECT * FROM cards WHERE widgetId IS NULL AND size = :size " +
            "ORDER BY pinAskedAt IS NULL, pinAskedAt DESC, createdAt DESC LIMIT 1"
    )
    suspend fun claimable(size: String): Card?

    @Query("UPDATE cards SET name = :name, size = :size, spec = :spec, updatedAt = :now WHERE id = :id")
    suspend fun redraw(id: Long, name: String, size: String, spec: String, now: Long)

    @Query("UPDATE cards SET own = :own, updatedAt = :now WHERE id = :id")
    suspend fun setOwn(id: Long, own: String, now: Long)

    @Query("UPDATE cards SET pinAskedAt = :at WHERE id = :id")
    suspend fun askedPin(id: Long, at: Long)

    @Query("UPDATE cards SET widgetId = :widgetId WHERE id = :id")
    suspend fun place(id: Long, widgetId: Int)

    /** 他把那一块从桌面上拿掉了：说明留着，能再钉 */
    @Query("UPDATE cards SET widgetId = NULL WHERE widgetId = :widgetId")
    suspend fun unplace(widgetId: Int)
}

/**
 * v1 是「一张卡片钉一条提醒、两种写死的模板」（11-14），只在 debug 包上出现过；v2 换成模型拼的说明，
 * 两样对不上，迁移时整张表重建（debug 包桌面上那两块会变成「还没钉东西」）。往后改表照 `ReminderDatabase` 的规矩：
 * 加一条 AutoMigration，不许 destructive。
 */
@Database(entities = [Card::class], version = 2, exportSchema = true)
abstract class CardDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao

    companion object {
        @Volatile private var instance: CardDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `cards`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`size` TEXT NOT NULL, `spec` TEXT NOT NULL, `own` TEXT NOT NULL, `widgetId` INTEGER, `pinAskedAt` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cards_widgetId` ON `cards` (`widgetId`)")
            }
        }

        fun get(context: Context): CardDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, CardDatabase::class.java, "card.db")
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}

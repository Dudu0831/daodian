package com.abc.daodian.reminder.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Reminder::class, FireLog::class, ReminderItem::class],
    version = 5,
    exportSchema = true,
    // 每次改表都得在这儿加一条迁移，否则覆盖安装后一打开就崩（库里已经是真实的提醒了，不能 destructive）。
    // 纯加列用 AutoMigration；改名 / 删列 / 要换算的手写 Migration
    // 1 → 2：reminders 加 gentle（轻提醒），老数据一律 0 = 响铃
    // 2 → 3：reminders 加 seriesNextAt（重复提醒稍后时本来的下一次），老数据一律 NULL = 没在稍后
    // 3 → 4：reminders 加 nudges（提前提醒的那几天，老数据 NULL）、endsOnDay（到了那天就结束，老数据 0 = 照旧顺延）
    // 4 → 5：一种提醒、六件事（§4.6），整表换成新的几列、逐行换算（[Migration4To5]）；加清单的表 reminder_items（§4.5）
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)]
)
@TypeConverters(Converters::class)
abstract class ReminderDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun fireLogDao(): FireLogDao
    abstract fun itemDao(): ReminderItemDao

    companion object {
        @Volatile private var instance: ReminderDatabase? = null

        fun get(context: Context): ReminderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReminderDatabase::class.java,
                    "reminder.db"
                ).addMigrations(Migration4To5).build().also { instance = it }
            }
    }
}

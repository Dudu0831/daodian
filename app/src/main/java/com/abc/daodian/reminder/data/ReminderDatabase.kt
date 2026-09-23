package com.abc.daodian.reminder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Reminder::class, FireLog::class],
    version = 1,
    exportSchema = true,
    // 每次改表都得在这儿加一条 AutoMigration(from = N, to = N + 1)，否则覆盖安装后一打开就崩
    // （库里已经是真实的提醒了，不能 destructive）。纯加列用 AutoMigration；改名 / 删列要写 spec，见 Room 文档
    autoMigrations = []
)
@TypeConverters(Converters::class)
abstract class ReminderDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun fireLogDao(): FireLogDao

    companion object {
        @Volatile private var instance: ReminderDatabase? = null

        fun get(context: Context): ReminderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReminderDatabase::class.java,
                    "reminder.db"
                ).build().also { instance = it }
            }
    }
}

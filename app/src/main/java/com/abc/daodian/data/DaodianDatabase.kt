package com.abc.daodian.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Reminder::class, FireLog::class],
    version = 2,
    exportSchema = true,
    // 每次改表都得在这儿加一条，否则覆盖安装后一打开就崩（库里已经是真实的提醒了，不能 destructive）。
    // 纯加列用 AutoMigration；改名 / 删列要写 spec，见 Room 文档
    autoMigrations = [
        AutoMigration(from = 1, to = 2)   // + reminders.dueDay（当天事项）
    ]
)
@TypeConverters(Converters::class)
abstract class DaodianDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun fireLogDao(): FireLogDao

    companion object {
        @Volatile private var instance: DaodianDatabase? = null

        fun get(context: Context): DaodianDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DaodianDatabase::class.java,
                    "daodian.db"
                ).build().also { instance = it }
            }
    }
}

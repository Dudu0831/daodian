package com.abc.daodian.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Reminder::class, FireLog::class],
    version = 1,
    exportSchema = true
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

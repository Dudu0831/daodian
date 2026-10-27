package com.abc.daodian.intake.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 通知监听层自己的库 `intake.db`：规则、收件箱、交接（DESIGN.md §12）。不碰 `ledger.db`、`reminder.db` ——
 * 模块要原文就自己复制一份（§2.2 库互不牵连）。
 *
 * 改表：version + 1，这里加 `autoMigrations = [AutoMigration(from = 1, to = 2)]` 之类，不能 destructive
 */
@Database(
    entities = [RuleRow::class, NoticeRow::class, NoticeRuleRow::class, HandoffRow::class],
    version = 1,
    exportSchema = true
)
abstract class IntakeDatabase : RoomDatabase() {
    abstract fun dao(): IntakeDao

    companion object {
        @Volatile private var instance: IntakeDatabase? = null

        fun get(context: Context): IntakeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, IntakeDatabase::class.java, "intake.db")
                    .build().also { instance = it }
            }
    }
}

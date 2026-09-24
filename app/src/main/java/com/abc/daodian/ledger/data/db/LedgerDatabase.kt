package com.abc.daodian.ledger.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 记账单独一个库 `ledger.db`：不碰 `reminder.db`（提醒，不许出错的那部分）和 `chat.db`。
 *
 * 改表：version + 1，并在这里加 `autoMigrations = [AutoMigration(from = 1, to = 2)]` 之类 ——
 * 库里是真实的账，不能 destructive。
 */
@Database(
    entities = [
        RawNotification::class, Txn::class, Allocation::class, TxnRaw::class, TxnLink::class,
        Category::class, Merchant::class, MerchantAlias::class, Account::class, Tag::class, TxnTag::class,
        ChangeLog::class, AgentRun::class
    ],
    version = 1,
    exportSchema = true
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        @Volatile private var instance: LedgerDatabase? = null

        fun get(context: Context): LedgerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "ledger.db")
                    .addCallback(Presets)
                    .build().also { instance = it }
            }
    }

    /** 建库时写进预设类别（DESIGN.md §10.4）。只在第一次建库时跑 */
    private object Presets : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            var sort = 0
            fun insert(name: String, parent: Long?, kind: String): Long {
                db.execSQL(
                    "INSERT INTO category (name, parentId, kind, sort, archived, createdBy) VALUES (?, ?, ?, ?, 0, 'PRESET')",
                    arrayOf(name, parent, kind, sort++)
                )
                return db.query("SELECT last_insert_rowid()").use { it.moveToFirst(); it.getLong(0) }
            }
            for ((top, subs) in OUT) {
                val id = insert(top, null, "OUT")
                subs.forEach { insert(it, id, "OUT") }
            }
            IN.forEach { insert(it, null, "IN") }
        }

        /** 一级是你在界面上看的「吃穿住行」；二级给模型定个调，它还能自己加 */
        val OUT = listOf(
            "餐饮" to listOf("外卖", "堂食", "咖啡饮品", "买菜", "零食"),
            "交通" to listOf("打车", "公共交通", "停车", "加油", "火车飞机"),
            "购物" to listOf("服饰", "数码", "家居", "网购"),
            "日用" to listOf("超市日用", "理发", "快递"),
            "娱乐" to listOf("电影演出", "游戏", "会员订阅"),
            "住房" to listOf("房租", "水电燃气", "物业", "话费网费"),
            "医疗" to listOf("药品", "门诊"),
            "其他" to emptyList()
        )

        val IN = listOf("工资", "利息理财", "转账红包", "其他收入")
    }
}

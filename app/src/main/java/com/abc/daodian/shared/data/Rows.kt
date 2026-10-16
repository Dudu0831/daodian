package com.abc.daodian.shared.data

import androidx.sqlite.db.SupportSQLiteDatabase

/** 跑一句 SQL，每行读成一列字符串（空的是 null）。导出诊断里各模块数自己的库用 */
fun SupportSQLiteDatabase.rows(sql: String): List<List<String?>> =
    query(sql).use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) })
        }
    }

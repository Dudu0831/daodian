package com.abc.daodian.intake.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.intakeDataStore by preferencesDataStore("intake")

/**
 * 路由表：每个订阅者听哪些 app（包名），一个订阅者一个键 `apps.<id>`。默认谁都不听，全由你勾。
 * 每来一条通知都要查一次 —— DataStore 读过一次就在内存里，不回盘。
 */
object IntakeSettings {

    private const val PREFIX = "apps."

    private fun key(id: String) = stringSetPreferencesKey(PREFIX + id)

    private fun store(context: Context) = context.applicationContext.intakeDataStore

    /** 整张表：订阅者 id → 包名 */
    fun routesFlow(context: Context): Flow<Map<String, Set<String>>> = store(context).data.map(::routesOf)

    suspend fun routes(context: Context): Map<String, Set<String>> = routesFlow(context).first()

    fun appsFlow(context: Context, id: String): Flow<Set<String>> = store(context).data.map { it[key(id)] ?: emptySet() }

    suspend fun setApp(context: Context, id: String, pkg: String, on: Boolean) {
        store(context).edit { p ->
            val now = p[key(id)] ?: emptySet()
            p[key(id)] = if (on) now + pkg else now - pkg
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun routesOf(p: Preferences): Map<String, Set<String>> =
        p.asMap().entries
            .filter { it.key.name.startsWith(PREFIX) }
            .associate { (k, v) -> k.name.removePrefix(PREFIX) to (v as? Set<String>).orEmpty() }
}

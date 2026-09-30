package com.abc.daodian.reminder.relay

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.relayDataStore by preferencesDataStore("relay")

/** 派活听谁：哪个 app、通知上她叫什么、暗号。默认什么都不听 */
object RelaySettings {

    data class Values(
        /** 听哪些 app（包名），和记账勾的那些分开存 */
        val apps: Set<String>,
        /** 通知标题上她的名字（微信是备注名）。空 = 谁都不听 */
        val who: String,
        /** 暗号。空 = 她发的每一句都交给模型判断是不是要你做的事；填了 = 只接这几个字开头的 */
        val code: String
    )

    private val APPS = stringSetPreferencesKey("apps")
    private val WHO = stringPreferencesKey("who")
    private val CODE = stringPreferencesKey("code")

    private fun store(context: Context) = context.applicationContext.relayDataStore

    fun flow(context: Context): Flow<Values> = store(context).data.map {
        Values(it[APPS] ?: emptySet(), it[WHO].orEmpty(), it[CODE].orEmpty())
    }

    suspend fun read(context: Context): Values = flow(context).first()

    suspend fun setApp(context: Context, pkg: String, on: Boolean) {
        store(context).edit { p ->
            val now = p[APPS] ?: emptySet()
            p[APPS] = if (on) now + pkg else now - pkg
        }
    }

    suspend fun setWho(context: Context, who: String) {
        store(context).edit { it[WHO] = who.trim() }
    }

    suspend fun setCode(context: Context, code: String) {
        store(context).edit { it[CODE] = code.trim() }
    }
}

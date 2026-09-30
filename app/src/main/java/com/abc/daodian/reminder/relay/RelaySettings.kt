package com.abc.daodian.reminder.relay

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.relayDataStore by preferencesDataStore("relay")

/** 派活听谁：通知上她叫什么、暗号。听哪些 app 在通知监听层勾（DESIGN.md §2.3）。默认谁都不听 */
object RelaySettings {

    data class Values(
        /** 通知标题上她的名字（微信是备注名）。空 = 谁都不听 */
        val who: String,
        /** 暗号。空 = 她发的每一句都交给模型判断是不是要你做的事；填了 = 只接这几个字开头的 */
        val code: String
    )

    private val WHO = stringPreferencesKey("who")
    private val CODE = stringPreferencesKey("code")

    private fun store(context: Context) = context.applicationContext.relayDataStore

    fun flow(context: Context): Flow<Values> = store(context).data.map {
        Values(it[WHO].orEmpty(), it[CODE].orEmpty())
    }

    suspend fun read(context: Context): Values = flow(context).first()

    suspend fun setWho(context: Context, who: String) {
        store(context).edit { it[WHO] = who.trim() }
    }

    suspend fun setCode(context: Context, code: String) {
        store(context).edit { it[CODE] = code.trim() }
    }
}

package com.abc.daodian.harness.permission

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.permissionDataStore by preferencesDataStore("permission")

/** 授权模式存在机器上。设置页那个开关改它；没存过就是 [PermissionMode.ASK] */
object PermissionStore {

    private val MODE = stringPreferencesKey("mode")

    fun flow(context: Context): Flow<PermissionMode> =
        context.applicationContext.permissionDataStore.data.map { p ->
            PermissionMode.entries.firstOrNull { it.name == p[MODE] } ?: PermissionMode.ASK
        }

    suspend fun save(context: Context, mode: PermissionMode) {
        context.applicationContext.permissionDataStore.edit { it[MODE] = mode.name }
    }
}

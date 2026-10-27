package com.abc.daodian.intake

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.abc.daodian.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.routingDataStore by preferencesDataStore("notice_routing")

/**
 * 收到的通知走哪条路（NOTICE_PLAN 第 6 步）：
 *  - [LEGACY]：老的订阅者（记账、派活），照你在「谁在听」里勾的分
 *  - [HUB]：新链路，硬条件 → 收件箱 → 通知管家 → 模块子代理（DESIGN.md §12）。老的订阅者一条都不收，
 *    不会两边各建一条提醒
 *
 * 只有 debug 包能切（「权限与监听」页最底下），默认 [LEGACY]；release 永远 [LEGACY]。
 * 切换那一版（第 8 步）整个删掉，只剩新链路
 */
enum class NoticeRouting {
    LEGACY, HUB;

    companion object {

        private val KEY = booleanPreferencesKey("hub")

        private fun store(context: Context) = context.applicationContext.routingDataStore

        fun flow(context: Context): Flow<NoticeRouting> =
            store(context).data.map { if (BuildConfig.DEBUG && it[KEY] == true) HUB else LEGACY }

        /** 每来一条都问一次：DataStore 读过一次就在内存里，不回盘 */
        suspend fun hub(context: Context): Boolean = BuildConfig.DEBUG && flow(context).first() == HUB

        suspend fun set(context: Context, routing: NoticeRouting) {
            if (!BuildConfig.DEBUG) return
            store(context).edit { it[KEY] = routing == HUB }
        }
    }
}

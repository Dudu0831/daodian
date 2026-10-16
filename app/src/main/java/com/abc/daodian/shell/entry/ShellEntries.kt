package com.abc.daodian.shell.entry

import android.content.Context
import kotlinx.coroutines.CancellationException

/**
 * 各模块给壳的那几份（[ShellEntry]），按根目录 `Modules.kt` 的顺序：抽屉里的纸、设置页的行、权限页的组都按这个顺序摆。
 * `DaodianApp.onCreate` 装进来。
 */
object ShellEntries {

    @Volatile
    var entries: List<ShellEntry> = emptyList()
        private set

    fun install(list: List<ShellEntry>) {
        require(list.map { it.id }.distinct().size == list.size) { "模块 id 重复：${list.map { it.id }}" }
        entries = list
    }

    fun health(context: Context): List<HealthItem> = entries.flatMap { it.health(context) }

    /** 各模块的诊断段（模块名 to 那一段）。哪个模块读库出错就把错写进去，不连累别的 */
    suspend fun diagnostics(context: Context): List<Pair<String, String>> =
        entries.mapNotNull { e ->
            val text = try {
                e.diagnostics(context)
            } catch (x: CancellationException) {
                throw x
            } catch (x: Exception) {
                "读不出来：${x.javaClass.simpleName} ${x.message}"
            }
            text?.takeIf { it.isNotBlank() }?.let { e.label to it }
        }
}

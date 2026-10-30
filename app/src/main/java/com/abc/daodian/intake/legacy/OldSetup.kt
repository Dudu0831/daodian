package com.abc.daodian.intake.legacy

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

// 0.1.x 那两份 DataStore 的文件名，原样照旧，不然读不到
private val Context.oldIntakeStore by preferencesDataStore("intake")
private val Context.oldRelayStore by preferencesDataStore("relay")

/**
 * 0.1.x 在设置里勾的：记账听哪些 app；派活听哪些 app、名单上谁、各自的暗号。
 * 0.2.0 不搬（DESIGN.md §12「旧配置不搬」），只在通知管家页顶上照着列一行，点了垫成一句话，你自己在对话里说。
 * 只读不写。0.2.1 连这个文件、页上那一组一起删（docs/cleanup.md）。
 *
 * 存法照删掉的 `IntakeSettings`（`intake` 里 `apps.<订阅者 id>` 一个包名集合）和 `RelaySettings`
 * （`relay` 里 `people`，一人一串「id␟名字␟暗号」；更早只听一个人时是 `who` / `code` 两个键）。
 */
object OldSetup {

    data class Person(val name: String, val code: String)

    /** [ledgerApps]、[relayApps] 是包名 */
    data class Setup(val ledgerApps: List<String>, val relayApps: List<String>, val people: List<Person>) {
        val hasLedger: Boolean get() = ledgerApps.isNotEmpty()
        val hasRelay: Boolean get() = people.isNotEmpty()
        val isEmpty: Boolean get() = !hasLedger && !hasRelay
    }

    private const val SEP = '\u001F'

    /** 读不出来（文件坏了、格式不认识）就当什么都没配过 */
    suspend fun read(context: Context): Setup = runCatching {
        val app = context.applicationContext
        val intake = app.oldIntakeStore.data.first()
        val relay = app.oldRelayStore.data.first()
        Setup(
            ledgerApps = intake[stringSetPreferencesKey("apps.ledger")].orEmpty().sorted(),
            relayApps = intake[stringSetPreferencesKey("apps.relay")].orEmpty().sorted(),
            people = people(
                relay[stringSetPreferencesKey("people")],
                relay[stringPreferencesKey("who")],
                relay[stringPreferencesKey("code")]
            )
        )
    }.getOrDefault(Setup(emptyList(), emptyList(), emptyList()))

    /** 名单按加上的先后排；还没照新样子存过（null）就看只听一个人那一版的两个键 */
    internal fun people(entries: Set<String>?, who: String?, code: String?): List<Person> {
        if (entries == null) {
            val name = who?.trim().orEmpty().ifEmpty { return emptyList() }
            return listOf(Person(name, code?.trim().orEmpty()))
        }
        return entries.mapNotNull { e ->
            val parts = e.split(SEP)
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to Person(name, parts.getOrNull(2).orEmpty())
        }.sortedBy { it.first }.map { it.second }
    }

    /** 名单那一行的字：「老婆（暗号 #）、妈」 */
    fun peopleText(people: List<Person>): String =
        people.joinToString("、") { if (it.code.isBlank()) it.name else "${it.name}（暗号 ${it.code}）" }

    /** 记账那一行点了垫进输入框的话 */
    fun ledgerSay(apps: List<String>): String = "帮我监听${apps.joinToString("、")}的通知，记账用"

    /** 派活那一行点了垫进输入框的话。[apps] 是 app 的名字，空着就不提从哪听 */
    fun relaySay(apps: List<String>, people: List<Person>): String {
        val from = if (apps.isEmpty()) "" else "${apps.joinToString("、")}里"
        val codes = people.filter { it.code.isNotBlank() }
        val tail = if (codes.isEmpty()) "" else "，" + codes.joinToString("、") { "${it.name}的要以「${it.code}」开头" }
        return "帮我监听$from${people.joinToString("、") { it.name }}发来的消息，要我做的事提醒我$tail"
    }
}

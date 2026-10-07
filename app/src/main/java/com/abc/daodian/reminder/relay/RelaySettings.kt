package com.abc.daodian.reminder.relay

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.relayDataStore by preferencesDataStore("relay")

/**
 * 派活听谁：一张名单，每个人有通知上的名字和自己的暗号。听哪些 app 在通知监听层勾（DESIGN.md §2.3）。
 * 默认谁都不听。只能听一个人的那一版存的是 `who` / `code` 两个键，读的时候当成名单上的第一个人，
 * 名单一改就照新样子存、旧键删掉。
 */
object RelaySettings {

    data class Person(
        /** 加上那一刻的毫秒数，也当 id：路由、排先后用它，改名不变 */
        val id: Long,
        /** 通知标题上的名字（微信是备注名），认人就靠它一字不差 */
        val name: String,
        /** 暗号。空 = 这个人发的每一句都交给模型判断是不是要你做的事；填了 = 只接这几个字开头的 */
        val code: String
    )

    const val TAKEN = "已经在听这个名字了"
    const val BLANK = "名字不能空着"

    /** 只听一个人那一版搬过来的那个人 */
    internal const val LEGACY_ID = 1L

    /** 一个人存成一个字符串时，三段之间的分隔符（打不出来的字，名字、暗号里不会有） */
    private const val SEP = '\u001F'

    private val PEOPLE = stringSetPreferencesKey("people")
    private val WHO = stringPreferencesKey("who")
    private val CODE = stringPreferencesKey("code")

    private fun store(context: Context) = context.applicationContext.relayDataStore

    /** 名单，按加上的先后排 */
    fun flow(context: Context): Flow<List<Person>> = store(context).data.map(::peopleOf)

    suspend fun read(context: Context): List<Person> = flow(context).first()

    /** 加一个人。重名、空名字不加，返回原因；加上了返回 null */
    suspend fun add(context: Context, name: String, code: String): String? {
        val n = clean(name).ifEmpty { return BLANK }
        var problem: String? = null
        store(context).edit { p ->
            val now = peopleOf(p)
            if (now.any { it.name == n }) {
                problem = TAKEN
                return@edit
            }
            val id = maxOf(System.currentTimeMillis(), (now.maxOfOrNull { it.id } ?: 0L) + 1)
            save(p, now + Person(id, n, clean(code)))
        }
        return problem
    }

    /** 改名字。和名单上别人重名、空名字不改，返回原因 */
    suspend fun rename(context: Context, id: Long, name: String): String? {
        val n = clean(name).ifEmpty { return BLANK }
        var problem: String? = null
        store(context).edit { p ->
            val now = peopleOf(p)
            if (now.any { it.id != id && it.name == n }) {
                problem = TAKEN
                return@edit
            }
            save(p, now.map { if (it.id == id) it.copy(name = n) else it })
        }
        return problem
    }

    suspend fun setCode(context: Context, id: Long, code: String) {
        store(context).edit { p -> save(p, peopleOf(p).map { if (it.id == id) it.copy(code = clean(code)) else it }) }
    }

    suspend fun remove(context: Context, id: Long) {
        store(context).edit { p -> save(p, peopleOf(p).filter { it.id != id }) }
    }

    /** 撤销「不听了」：原样放回去。这会儿已经有同名的就不放了 */
    suspend fun restore(context: Context, person: Person) {
        store(context).edit { p ->
            val now = peopleOf(p)
            if (now.none { it.id == person.id || it.name == person.name }) save(p, now + person)
        }
    }

    private fun peopleOf(p: Preferences): List<Person> = decode(p[PEOPLE], p[WHO], p[CODE])

    private fun save(p: MutablePreferences, people: List<Person>) {
        p[PEOPLE] = people.map(::encode).toSet()
        p.remove(WHO)
        p.remove(CODE)
    }

    private fun clean(s: String): String = s.replace(SEP.toString(), "").trim()

    internal fun encode(person: Person): String = "${person.id}$SEP${person.name}$SEP${person.code}"

    /** [entries] 是新样子存的名单；还没存过（null）就看旧的那两个键 */
    internal fun decode(entries: Set<String>?, legacyWho: String?, legacyCode: String?): List<Person> {
        if (entries == null) {
            val who = legacyWho?.trim().orEmpty().ifEmpty { return emptyList() }
            return listOf(Person(LEGACY_ID, who, legacyCode?.trim().orEmpty()))
        }
        return entries.mapNotNull { e ->
            val parts = e.split(SEP)
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Person(id, name, parts.getOrNull(2).orEmpty())
        }.sortedBy { it.id }
    }
}

package com.abc.daodian.reminder.presentation.relay

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.reminder.relay.Relay
import com.abc.daodian.reminder.relay.RelayDatabase
import com.abc.daodian.reminder.relay.RelayMessage
import com.abc.daodian.reminder.relay.RelaySettings
import com.abc.daodian.reminder.relay.RelaySettings.Person
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 派活的名单和每个人发来的：提醒设置页「派活」那一组和某个人的那一页（[RelayPersonScreen]）共用一份，
 * 按 Activity 取 —— 在那个人的页上点了「不听了」，撤销条画在退回去的设置页上。
 * 通知使用权、听哪些 app 归通知监听层（DESIGN.md §2.3）。
 */
class RelayViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = RelayDatabase.get(app).dao()

    /** 名单。null = 还没读出来，别先画成一个人都没有 */
    val people: StateFlow<List<Person>?> = RelaySettings.flow(app).map<List<Person>, List<Person>?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 每个名字发来过几句，名单那几行右边写它 */
    val counts: StateFlow<Map<String, Int>> = dao.counts().map { rows -> rows.associate { it.who to it.n } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 最近发过消息的名字，加人、改名字时点一下填进去 */
    val seen = Relay.seen

    /** 刚点了「不听了」的那个人和发来过的，留着给撤销 */
    class Forgotten(val person: Person, val messages: List<RelayMessage>)

    private val _forgotten = MutableStateFlow<Forgotten?>(null)
    val forgotten: StateFlow<Forgotten?> = _forgotten.asStateFlow()

    fun messagesOf(name: String): Flow<List<RelayMessage>> = dao.recentOf(name)

    /** 加一个人。没加上返回原因（界面写在名字底下） */
    suspend fun add(name: String, code: String): String? = RelaySettings.add(getApplication(), name, code)

    /**
     * 改名字，发来过的记录跟着改。没改成返回原因。
     * 名单和记录是两处，改到一半框被关掉也要改完，不然那些记录就不算这个人的了
     */
    suspend fun rename(person: Person, name: String): String? = withContext(NonCancellable) {
        RelaySettings.rename(getApplication(), person.id, name)?.let { return@withContext it }
        val now = RelaySettings.read(getApplication()).firstOrNull { it.id == person.id }?.name
        if (now != null && now != person.name) dao.rename(person.name, now)
        null
    }

    fun setCode(person: Person, code: String) = viewModelScope.launch { RelaySettings.setCode(getApplication(), person.id, code) }

    /** 不听了：从名单上拿掉，发来过的一起删。删都不弹确认、给 5 秒撤销（DESIGN.md §08） */
    fun forget(person: Person) = viewModelScope.launch {
        val messages = dao.allOf(person.name)
        RelaySettings.remove(getApplication(), person.id)
        dao.clearOf(person.name)
        val f = Forgotten(person, messages)
        _forgotten.value = f
        delay(UNDO_MILLIS)
        if (_forgotten.value === f) _forgotten.value = null
    }

    fun undoForget() {
        val f = _forgotten.value ?: return
        _forgotten.value = null
        viewModelScope.launch {
            RelaySettings.restore(getApplication(), f.person)
            dao.insertAll(f.messages)
        }
    }

    fun retry(id: Long) = Relay.retry(getApplication(), id)

    fun clear(person: Person) = viewModelScope.launch { dao.clearOf(person.name) }

    private companion object {
        const val UNDO_MILLIS = 5_000L
    }
}

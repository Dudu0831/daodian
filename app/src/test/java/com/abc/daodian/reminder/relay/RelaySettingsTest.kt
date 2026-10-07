package com.abc.daodian.reminder.relay

import com.abc.daodian.reminder.relay.RelaySettings.Person
import org.junit.Assert.assertEquals
import org.junit.Test

/** 派活名单怎么存、只听一个人那一版的设置怎么搬过来 */
class RelaySettingsTest {

    @Test
    fun `只听一个人那一版的设置搬成名单上的第一个人`() {
        assertEquals(
            listOf(Person(RelaySettings.LEGACY_ID, "小美", "到点")),
            RelaySettings.decode(null, " 小美 ", "到点")
        )
        assertEquals(listOf(Person(RelaySettings.LEGACY_ID, "小美", "")), RelaySettings.decode(null, "小美", null))
    }

    @Test
    fun `旧版谁都没听就是空名单`() {
        assertEquals(emptyList<Person>(), RelaySettings.decode(null, null, null))
        assertEquals(emptyList<Person>(), RelaySettings.decode(null, "  ", "到点"))
    }

    @Test
    fun `名单存过之后不再看旧的那两个键`() {
        assertEquals(emptyList<Person>(), RelaySettings.decode(emptySet(), "小美", "到点"))
    }

    @Test
    fun `存进去再读出来一样，按加上的先后排`() {
        val people = listOf(Person(30, "王总", ""), Person(10, "小美", "到点"), Person(20, "妈 (老家)", "记一下：带伞"))
        val read = RelaySettings.decode(people.map(RelaySettings::encode).toSet(), null, null)
        assertEquals(people.sortedBy { it.id }, read)
    }

    @Test
    fun `读不懂的那一条跳过，别的照读`() {
        val entries = setOf(RelaySettings.encode(Person(10, "小美", "")), "乱的", "abc\u001F名字\u001F", "20\u001F \u001F到点")
        assertEquals(listOf(Person(10, "小美", "")), RelaySettings.decode(entries, null, null))
    }
}

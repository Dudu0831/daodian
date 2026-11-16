package com.abc.daodian.card.own

import com.abc.daodian.card.plugin.CardField
import com.abc.daodian.card.plugin.CardKind
import com.abc.daodian.card.plugin.CardRow
import com.abc.daodian.card.plugin.CardValue
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 卡片自己记的东西怎么记（说明里的 `own`）。见 DESIGN.md §8.5
 * [daily] = 每天零点清零（喝水、今天的清单）
 */
sealed interface OwnDef {
    /** 一个数：喝了几杯。[max] 给了就有「还差几」「够了没」 */
    data class Count(val start: Long = 0, val max: Long? = null, val daily: Boolean = false) : OwnDef

    /** 一张清单：买菜 */
    data class Checklist(val items: List<String>, val daily: Boolean = false) : OwnDef

    /** 一句话：今天的目标 */
    data class Note(val text: String) : OwnDef

    /** 一个日子：春节、他自己说的哪一天。和提醒无关的倒数用它 */
    data class Day(val date: LocalDate) : OwnDef

    /** 一个走秒：泡面 3 分钟。[seconds] 是点「开始」时默认数多久 */
    data class Timer(val seconds: Long) : OwnDef
}

/**
 * 一张卡片自己记的数：定义（[defs]，来自说明）+ 现在的值（存在库里 `Card.own`，一段 JSON）。
 *
 * 绑定写 `own.<键>`（`own.cups`），派生的写 `own.<键>.<后缀>`（`own.cups.left`）；动作写 `own.<做什么> <键> [参数]`
 * （`own.add cups 1`）。每天清零的，读和改之前先看存的那一天是不是今天，不是就先清掉 —— 不靠零点那一刻有没有人来。
 */
class OwnData(val defs: Map<String, OwnDef>, json: String?, private val today: LocalDate, private val now: Long) {

    private val values: ObjectNode = runCatching { mapper.readTree(json ?: "{}") as? ObjectNode }.getOrNull() ?: mapper.createObjectNode()

    init {
        defs.forEach { (key, def) -> normalize(key, def) }
    }

    fun json(): String = mapper.writeValueAsString(values)

    // ---------------- 读

    fun read(name: String): CardValue? {
        val key = name.substringBefore('.')
        val suffix = name.substringAfter('.', "")
        return when (val def = defs[key] ?: return null) {
            is OwnDef.Count -> {
                val n = count(key, def)
                when (suffix) {
                    "" -> CardValue.Number(n)
                    "max" -> def.max?.let { CardValue.Number(it) }
                    "left" -> def.max?.let { CardValue.Number((it - n).coerceAtLeast(0)) }
                    "full" -> def.max?.let { CardValue.Flag(n >= it) }
                    else -> null
                }
            }
            is OwnDef.Checklist -> {
                val items = items(key, def)
                val done = items.count { it.second }
                when (suffix) {
                    "" -> CardValue.Rows(items.mapIndexed { i, (t, d) -> CardRow(t, done = d, action = "own.toggle $key ${i + 1}") })
                    "done" -> CardValue.Number(done.toLong())
                    "total" -> CardValue.Number(items.size.toLong())
                    "left" -> CardValue.Number((items.size - done).toLong())
                    "all_done" -> CardValue.Flag(items.isNotEmpty() && done == items.size)
                    else -> null
                }
            }
            is OwnDef.Note -> if (suffix == "") CardValue.Text(values.path(key).path("t").asText(def.text)) else null
            is OwnDef.Day -> {
                val day = runCatching { LocalDate.parse(values.path(key).path("d").asText()) }.getOrNull() ?: def.date
                val days = ChronoUnit.DAYS.between(today, day)
                when (suffix) {
                    "" -> CardValue.Text(dateText(day, today))
                    "days_left" -> CardValue.Number(days.coerceAtLeast(0))
                    "days_past" -> CardValue.Number((-days).coerceAtLeast(0))
                    "is_today" -> CardValue.Flag(days == 0L)
                    "near" -> CardValue.Flag(days in 0..NEAR_DAYS)
                    "past" -> CardValue.Flag(days < 0)
                    else -> null
                }
            }
            is OwnDef.Timer -> when (suffix) {
                "running" -> CardValue.Flag(until(key)?.let { it > now } == true)
                "" -> CardValue.Text(clock(def.seconds))
                else -> null
            }
        }
    }

    /** 走秒到什么时候（epoch 毫秒）；没在走是 null */
    fun until(key: String): Long? = values.path(key).path("until").takeIf { it.isNumber }?.asLong()

    // ---------------- 改

    /**
     * 改一样。[op]：add（加几，默认 1）、set（设成几 / 一句话 / 一个日子）、toggle（勾掉第几项，从 1 数）、
     * add_item、remove_item（第几项）、reset（清零、清单全取消勾）、start（走秒开始，默认数说明里的秒数）、stop。
     * 不对的回一句原因，办成了 null
     */
    fun apply(op: String, key: String, arg: String?): String? {
        val def = defs[key] ?: return "没有叫 $key 的（有：${defs.keys.joinToString("、").ifEmpty { "没有" }}）"
        val v = values.with(key)
        when (def) {
            is OwnDef.Count -> {
                val n = count(key, def)
                val next = when (op) {
                    "add" -> n + (arg?.toLongOrNull() ?: 1)
                    "set" -> arg?.toLongOrNull() ?: return "set 要给一个数"
                    "reset" -> def.start
                    else -> return "$key 是一个数，只能 add、set、reset"
                }
                v.put("n", def.max?.let { next.coerceIn(0, it) } ?: next.coerceAtLeast(0))
            }
            is OwnDef.Checklist -> {
                val items = items(key, def).toMutableList()
                fun index(): Int? = arg?.trim()?.toIntOrNull()?.minus(1)?.takeIf { it in items.indices }
                when (op) {
                    "toggle" -> { val i = index() ?: return "toggle 要给第几项（从 1 数，现在有 ${items.size} 项）"; items[i] = items[i].first to !items[i].second }
                    "add_item" -> { val t = arg?.trim()?.takeIf { it.isNotEmpty() } ?: return "add_item 要给这一项的字"; if (items.size >= MAX_ITEMS) return "清单最多 $MAX_ITEMS 项"; items += t to false }
                    "remove_item" -> { val i = index() ?: return "remove_item 要给第几项（从 1 数）"; items.removeAt(i) }
                    "reset" -> items.replaceAll { it.first to false }
                    else -> return "$key 是一张清单，只能 toggle、add_item、remove_item、reset"
                }
                v.set<ObjectNode>("items", mapper.createArrayNode().apply { items.forEach { (t, d) -> addObject().put("t", t).put("d", d) } })
            }
            is OwnDef.Note -> if (op == "set") v.put("t", arg.orEmpty().take(40)) else return "$key 是一句话，只能 set"
            is OwnDef.Day -> {
                if (op != "set") return "$key 是一个日子，只能 set"
                val d = runCatching { LocalDate.parse(arg?.trim()?.take(10)) }.getOrNull() ?: return "set 要给 YYYY-MM-DD"
                v.put("d", d.toString())
            }
            is OwnDef.Timer -> when (op) {
                "start" -> v.put("until", now + 1000 * (arg?.toLongOrNull() ?: def.seconds).coerceIn(1, MAX_TIMER_SECONDS))
                "stop", "reset" -> v.remove("until")
                else -> return "$key 是走秒，只能 start、stop"
            }
        }
        v.put("day", today.toString())
        return null
    }

    // ---------------- 内部

    private fun count(key: String, def: OwnDef.Count): Long = values.path(key).path("n").takeIf { it.isNumber }?.asLong() ?: def.start

    private fun items(key: String, def: OwnDef.Checklist): List<Pair<String, Boolean>> {
        val stored = values.path(key).path("items")
        if (!stored.isArray) return def.items.map { it to false }
        return stored.map { it.path("t").asText() to it.path("d").asBoolean(false) }
    }

    /** 每天清零的，存的不是今天就先清 */
    private fun normalize(key: String, def: OwnDef) {
        val daily = (def as? OwnDef.Count)?.daily ?: (def as? OwnDef.Checklist)?.daily ?: false
        if (!daily) return
        val day = values.path(key).path("day").asText("")
        if (day.isEmpty() || day == today.toString()) return
        apply("reset", key, null)
    }

    companion object {
        private val mapper = ObjectMapper()

        const val NEAR_DAYS = 7L
        const val MAX_ITEMS = 12
        const val MAX_TIMER_SECONDS = 24 * 3600L

        private val KEY = Regex("[a-z][a-z0-9_]{0,19}")

        fun validKey(key: String) = KEY.matches(key)

        /** 「2 月 13 日」，不是今年的带年份 */
        fun dateText(day: LocalDate, today: LocalDate): String =
            (if (day.year == today.year) "" else "${day.year} 年 ") + "${day.monthValue} 月 ${day.dayOfMonth} 日"

        fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

        /** 说明里这几样能绑哪些名字（不带 own. 前缀），校验和工具包说明用 */
        fun fields(defs: Map<String, OwnDef>): List<CardField> = defs.flatMap { (k, def) ->
            when (def) {
                is OwnDef.Count -> listOf(CardField(k, CardKind.NUMBER, "几")) + if (def.max == null) emptyList() else listOf(
                    CardField("$k.max", CardKind.NUMBER, "上限"),
                    CardField("$k.left", CardKind.NUMBER, "还差几"),
                    CardField("$k.full", CardKind.FLAG, "够了没")
                )
                is OwnDef.Checklist -> listOf(
                    CardField(k, CardKind.ROWS, "清单的几行"),
                    CardField("$k.done", CardKind.NUMBER, "勾了几项"),
                    CardField("$k.total", CardKind.NUMBER, "一共几项"),
                    CardField("$k.left", CardKind.NUMBER, "还剩几项"),
                    CardField("$k.all_done", CardKind.FLAG, "全勾完了没")
                )
                is OwnDef.Note -> listOf(CardField(k, CardKind.TEXT, "那句话"))
                is OwnDef.Day -> listOf(
                    CardField(k, CardKind.TEXT, "那一天"),
                    CardField("$k.days_left", CardKind.NUMBER, "还剩几天"),
                    CardField("$k.days_past", CardKind.NUMBER, "过了几天"),
                    CardField("$k.is_today", CardKind.FLAG, "是不是今天"),
                    CardField("$k.near", CardKind.FLAG, "最后 7 天"),
                    CardField("$k.past", CardKind.FLAG, "过了没")
                )
                is OwnDef.Timer -> listOf(
                    CardField(k, CardKind.TEXT, "默认的时长「3:00」"),
                    CardField("$k.running", CardKind.FLAG, "在走没")
                )
            }
        }

        /** 按钮能做的（`own.add cups 1`）。第一个参数是键 */
        val ACTIONS = listOf(
            CardField("add", CardKind.ACTION, "数加几", "键 [几]"),
            CardField("set", CardKind.ACTION, "数设成几", "键 几"),
            CardField("toggle", CardKind.ACTION, "勾掉 / 取消清单第几项", "键 第几项"),
            CardField("reset", CardKind.ACTION, "清零、清单全取消勾", "键"),
            CardField("start", CardKind.ACTION, "走秒开始", "键 [秒数]"),
            CardField("stop", CardKind.ACTION, "走秒停下", "键")
        )
    }
}

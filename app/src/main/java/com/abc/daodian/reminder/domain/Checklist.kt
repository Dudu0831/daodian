package com.abc.daodian.reminder.domain

import com.abc.daodian.reminder.data.ReminderItem

/**
 * 清单（§4.5）的规矩和几处要写的字，纯函数。清单是一条提醒底下的几样，勾完不等于办完：提醒照样到点响。
 */
object Checklist {

    const val MAX_ITEMS = 30
    const val MAX_CHARS = 20

    /** 一样怎么存：去掉两头空白、截到 [MAX_CHARS] 个字；空的不要 */
    fun clean(text: String): String? = text.trim().take(MAX_CHARS).takeIf { it.isNotEmpty() }

    /** 模型列的一整份：有不合规矩的回一句原因（让它自己改），没有就是 null */
    fun problemOf(texts: List<String>): String? {
        val kept = texts.map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            kept.size > MAX_ITEMS -> "清单最多 $MAX_ITEMS 样，列了 ${kept.size} 样，删掉些不要紧的。"
            kept.any { it.length > MAX_CHARS } -> "清单一样最多 $MAX_CHARS 个字，「${kept.first { it.length > MAX_CHARS }}」太长，写短点。"
            else -> null
        }
    }

    fun left(items: List<ReminderItem>): Int = items.count { !it.done }

    /**
     * 到点那条通知上写的：「还剩 5 样：护照签证、厚外套、雨伞」；全勾完「清单都勾完了」；没有清单是 null。
     * [atMost] 列几样（全屏页 4 样），多了写「等 N 样」
     */
    fun remaining(items: List<ReminderItem>, atMost: Int = Int.MAX_VALUE): String? {
        if (items.isEmpty()) return null
        val open = items.filterNot { it.done }
        if (open.isEmpty()) return "清单都勾完了"
        val names = open.take(atMost).joinToString("、") { it.text }
        return "还剩 ${open.size} 样：$names" + if (open.size > atMost) " 等" else ""
    }

    /** 只说几样（提前提醒那条、提醒页那一行）：「清单还剩 5 样」「清单都勾完了」；没有清单是 null */
    fun count(items: List<ReminderItem>): String? = when {
        items.isEmpty() -> null
        left(items) == 0 -> "清单都勾完了"
        else -> "清单还剩 ${left(items)} 样"
    }
}

/**
 * 对话里改清单（`change_checklist`）：几号勾上、几号取消、加哪几样、删几号。号是 `list_reminders` 列的顺序，从 1 起
 */
data class ChecklistEdit(
    val check: List<Int> = emptyList(),
    val uncheck: List<Int> = emptyList(),
    val add: List<String> = emptyList(),
    val remove: List<Int> = emptyList()
) {
    /** 照现在这份 [items] 算出要动哪几行。号对不上的、加满了加不进去的都记下来回给模型 */
    fun plan(items: List<ReminderItem>): Plan {
        fun at(n: Int) = items.getOrNull(n - 1)
        val unknown = (check + uncheck + remove).filter { at(it) == null }.distinct()
        val removed = remove.mapNotNull(::at).distinct()
        val checked = check.mapNotNull(::at).filter { !it.done && it !in removed }.distinct()
        val unchecked = uncheck.mapNotNull(::at).filter { it.done && it !in removed }.distinct()
        val room = Checklist.MAX_ITEMS - (items.size - removed.size)
        val texts = add.mapNotNull(Checklist::clean)
        return Plan(
            checked = checked, unchecked = unchecked, removed = removed,
            added = texts.take(room.coerceAtLeast(0)), dropped = texts.drop(room.coerceAtLeast(0)), unknown = unknown
        )
    }

    data class Plan(
        val checked: List<ReminderItem>,
        val unchecked: List<ReminderItem>,
        val removed: List<ReminderItem>,
        val added: List<String>,
        /** 加满了（[Checklist.MAX_ITEMS]）没加进去的 */
        val dropped: List<String>,
        /** 没有这个号 */
        val unknown: List<Int>
    ) {
        val empty get() = checked.isEmpty() && unchecked.isEmpty() && removed.isEmpty() && added.isEmpty()
    }
}

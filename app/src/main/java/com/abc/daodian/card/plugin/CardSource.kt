package com.abc.daodian.card.plugin

import android.content.Context
import com.abc.daodian.card.Cards
import java.time.LocalDate

/**
 * 模块给桌面卡片的那一份：「我这里有东西可以钉到桌面上」。见 DESIGN.md §8.5
 *
 * 卡片模块（`card/`）管模板、桌面组件、登记和对话里的工具，不知道提醒、账是什么；钉的那件事是谁、哪一天、
 * 办没办完，问交它的模块（同通知管家的 `intake/plugin/NoticeTarget`）。模块在接头上交出去（`Module.cards`），
 * 加一个模块，能钉的东西自动多一种。
 */
interface CardSource {

    /** 「reminder」，和模块 id 一样。登记表里存它，存在手机上，不改 */
    val id: String

    /** 「提醒」 */
    val label: String

    /**
     * 给助手的一段：[ref] 是什么、怎么查到、还没有时怎么先建出来。拼进卡片工具包的说明（§6.11），
     * 所以卡片模块的说明不用跟着各模块的工具改
     */
    val brief: String

    /** [ref] 那件事此刻的样子。没有了（删了）是 null。每次重画都问，别在里面做慢事 */
    suspend fun subject(context: Context, ref: Long): CardSubject?

    /** 点卡片去哪（路由） */
    fun route(ref: Long): String
}

/** 钉在卡片上的一件事：叫什么、是哪一天、办完没有。剩几天由卡片自己算 */
data class CardSubject(val title: String, val day: LocalDate, val done: Boolean = false)

/**
 * 模块喊一声「我的东西变了」，桌面上的卡片重画。刷新是推的（桌面在别的进程，收不到 Room 的流，§8.2）：
 * 改标题、改日子、点完成、删掉之后都要喊。漏喊的后果是卡片上是旧字，到跨零点或桌面下一次来要时才对。
 */
object CardBoard {
    suspend fun changed(context: Context) = Cards.refresh(context)
}

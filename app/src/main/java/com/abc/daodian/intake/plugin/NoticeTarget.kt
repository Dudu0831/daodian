package com.abc.daodian.intake.plugin

import android.content.Context
import com.abc.daodian.intake.Notice

/**
 * 模块接通知管家交来的活（DESIGN.md §12.5）：管家只定交给谁，活由模块自己的子代理干。
 * 模块在接头上交出去（`Module.notice`），intake 不 import 模块；加一个模块，管家的去处清单自动多一行。
 */
interface NoticeTarget {

    /** 「ledger」「reminder」，和模块 id 一样。交接表里存它，存在手机上，不改 */
    val id: String

    /** 「记账」 */
    val label: String

    /** 给管家的一句：接什么、附言写什么。跟着去处走，管家的提示词不跟着模块改（§6.3 第 6 条） */
    val brief: String

    /**
     * 管家交来的几条。同一条可能交不止一次（上回交到一半进程没了），按 [Handed.handoffId] 防重复。
     * 办成的回一张 [Receipt]；没回执的算这次没交成，过一会儿再交 —— 模型没配、断网就别回执
     */
    suspend fun accept(context: Context, handed: List<Handed>): List<Receipt>
}

/**
 * 交来的一条
 *
 * @param notice 原样的那条：要存原文的（记账）存它
 * @param app 发通知的 app 的人话名字
 * @param title 整理过格式的标题（剥了「(3条)」）
 * @param body 整理过格式的正文（剥了「[3条]」「名字: 」）
 * @param note 管家的附言：「18:00 提醒取快递，标题带 3-2-1088」；可以是空的
 */
data class Handed(
    val handoffId: Long,
    val noticeId: Long,
    val notice: Notice,
    val app: String,
    val title: String?,
    val body: String?,
    val note: String
)

/** 当场的回执，写在收件箱记录页上：「待整理」「18:00 · 取快递」。后来才办完的用 [NoticeReports] 补 */
data class Receipt(val handoffId: Long, val text: String, val route: String? = null)

package com.abc.daodian.intake.core.format

import com.abc.daodian.intake.Notice

/**
 * 整理过格式的一条：硬条件比的、管家看的都是它；收件箱存的是 [raw] 原样的那条。见 DESIGN.md §12.1
 *
 * @param title 剥过的标题，空的是 null
 * @param body 剥过的正文，空的是 null
 */
data class Normalized(val raw: Notice, val title: String?, val body: String?) {

    /** 正文连上展开后的那段（[Notice.extra]）：「含 / 不含」比的是它，有的银行把明细写在展开后的大字里 */
    val fullText: String? = listOfNotNull(body, raw.extra.tidy()).joinToString("\n").ifEmpty { null }

    companion object {
        /** 原样，什么都没剥：流水线开头的样子 */
        fun of(raw: Notice) = Normalized(raw, raw.title, raw.text)
    }
}

/** 一种 app 的通知长什么样、怎么剥（Strategy）。按包名挑，见 [Formats] */
fun interface NoticeFormat {
    fun normalize(n: Notice): Normalized
}

/** 不认得格式的（银行、短信、快递）：只去掉首尾空白 */
object PlainFormat : NoticeFormat {
    override fun normalize(n: Notice) = Normalized(n, n.title.tidy(), n.text.tidy())
}

/** 去掉首尾空白，空的是 null */
internal fun String?.tidy(): String? = this?.trim()?.ifEmpty { null }

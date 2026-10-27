package com.abc.daodian.intake.core.format

import com.abc.daodian.intake.Notice

/**
 * 聊天软件的通知：标题后面的未读数「小美(2条新消息)」、正文前面的「[2条]」、正文开头和标题同名的「小美: 」都剥掉 ——
 * 剥完标题就是那个人，正文就是那句话，「标题等于小美」「开头是暗号」才比得准。
 * 群聊里别人的「张三: 」不剥：那是谁说的，管家要看。几条正则是从以前的派活挪来的
 */
object ChatFormat : NoticeFormat {

    override fun normalize(n: Notice): Normalized {
        val title = n.title?.replace(TITLE_COUNT, "").tidy()
        return Normalized(n, title, body(n.text, title))
    }

    private fun body(text: String?, who: String?): String? {
        val t = text?.replace(TEXT_COUNT, "").tidy() ?: return null
        if (who == null) return t
        val sep = SEPARATORS.firstOrNull { t.startsWith(who + it) } ?: return t
        return t.removePrefix(who + sep).tidy()
    }

    /** 标题后面带的未读数：「小美(2条新消息)」 */
    private val TITLE_COUNT = Regex("""\s*[（(]\d+\s*条[^）)]*[）)]\s*$""")

    /** 正文前面的未读数：「[2条]」 */
    private val TEXT_COUNT = Regex("""^\s*\[\d+\s*条]\s*""")

    private val SEPARATORS = listOf(": ", "：", ":")
}

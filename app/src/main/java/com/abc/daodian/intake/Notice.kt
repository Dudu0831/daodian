package com.abc.daodian.intake

/**
 * 监听收到的一条通知，原样的，不解析。同一条可能投不止一次（实时收一次，解锁、手动扫又扫到），
 * 订阅者按指纹去重。见 DESIGN.md §2.3
 */
data class Notice(
    val pkg: String,
    /** 系统给这条通知的 key，同一个 app 更新同一条通知时不变 */
    val key: String,
    /** 系统收到的时刻 */
    val postTime: Long,
    /** 发通知的 app 自己写的时刻（`Notification.when`），聊天软件是那条消息的时刻；没写就是 [postTime] */
    val at: Long,
    val title: String?,
    val text: String?,
    /** bigText、subText、消息样式里和正文不重复的部分 —— 有的银行把明细写在展开后的大字里 */
    val extra: String?,
    /** extras 里能变成文字的全收，JSON。要存原文的订阅者（记账）存它 */
    val extras: String,
    /** 正文被系统遮蔽成了「敏感数据已隐藏」。监听层过一会儿会重读、真正文到了再投一次 */
    val redacted: Boolean,
    /** 怎么抓到的：posted / active / unlock / organize / tap / retry+5s …… */
    val how: String
)

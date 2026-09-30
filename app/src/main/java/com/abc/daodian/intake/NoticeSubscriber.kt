package com.abc.daodian.intake

import android.content.Context

/**
 * 要听通知的模块实现它，在根目录 `Features.kt` 的 `SUBSCRIBERS` 里列一行就接上了。
 *
 * 监听层只按包名分：你给这个订阅者勾了哪些 app，那些 app 的通知就交给它（[accept]）。
 * 更细的规则 —— 听谁、暗号、认不认得这家银行的格式 —— 都是订阅者自己的事。见 DESIGN.md §2.3
 */
interface NoticeSubscriber {

    /** 存勾选、路由都用它：`ledger`、`relay`。改了等于换一个订阅者，之前勾的就没了 */
    val id: String

    /** 给人看的名字：「记账」「派活」 */
    val label: String

    /** 勾 app 那一页顶上那句：勾上的 app 的通知拿去干什么 */
    val purpose: String

    /** 勾选页上某个 app 底下的红字（记账：勾聊天软件会连聊天一起存下来）。没有就是 null */
    fun warn(context: Context, pkg: String): String? = null

    /** 勾选页上排在前面的（派活：聊天软件） */
    fun suggested(context: Context, pkg: String): Boolean = false

    /**
     * 收到一条它勾了的 app 的通知。**只做落库这种快事**：扫通知栏要等所有订阅者都返回才算扫完，
     * 叫模型这类重活自己另起。可能收到重复的，按指纹去重。抛出来的异常只影响自己这一条
     */
    suspend fun accept(context: Context, notice: Notice)
}

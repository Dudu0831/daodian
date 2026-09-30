package com.abc.daodian.ledger.capture

import android.content.Context
import com.abc.daodian.intake.Notice
import com.abc.daodian.intake.NoticeSubscriber
import com.abc.daodian.ledger.data.LedgerStore
import com.abc.daodian.shared.apps.AppCatalog

/**
 * 记账订阅通知：你给记账勾的那些 app 的通知，**原样**存进 `raw_notification`，不解析 ——
 * 读懂是整理 agent 的事（DESIGN.md §10.1）。怎么抓、听哪些 app、连没连着都归通知监听层（DESIGN.md §2.3）。
 *
 * 同一条通知同一段正文只存一次；遮蔽版和真正文的关系在 [LedgerStore.ingest] 里理清。
 */
object LedgerCapture : NoticeSubscriber {

    override val id = "ledger"

    override val label = "记账"

    override val purpose = "勾上的 app 发的通知，原样存下来交给模型整理成账；没勾的，一条都不存。银行、支付宝这类付了钱会发通知的 app 勾上，记账才收得到。"

    /** 付款通知和聊天是同一个 app 发的，代码分不开：只提醒，照样能勾 */
    override fun warn(context: Context, pkg: String): String? = when {
        AppCatalog.isChat(pkg) -> "聊天消息也会原样存下来、发给模型"
        AppCatalog.isSms(context, pkg) -> "验证码、别的短信也会原样存下来、发给模型"
        else -> null
    }

    override suspend fun accept(context: Context, notice: Notice) {
        LedgerStore.get(context).ingest(notice)
    }
}

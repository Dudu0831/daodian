package com.abc.daodian.ledger.assistant

import android.content.Context
import androidx.compose.runtime.Composable
import com.abc.daodian.assistant.plugin.AssistantPlugin
import com.abc.daodian.assistant.plugin.PendingTrigger
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceView
import com.abc.daodian.assistant.plugin.Trigger
import com.abc.daodian.ledger.LedgerModule
import com.abc.daodian.ledger.LedgerRoutes
import com.abc.daodian.ledger.data.LedgerStore
import com.abc.daodian.ledger.domain.LedgerText
import com.abc.daodian.ledger.presentation.AskTagRow
import com.abc.daodian.ledger.reconciliation.LedgerCheck
import com.abc.daodian.ledger.reconciliation.LedgerCheckPrompt
import com.abc.daodian.ledger.tools.LedgerTools
import com.abc.daodian.ledger.tools.LedgerTrace
import com.abc.daodian.runtime.ask.AskNote
import com.abc.daodian.runtime.tool.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 记账给助手的：查 / 记 / 改 / 加类别四个工具和它们留的痕、每句垫着的类别表和标签、每晚对账那一轮、
 * 对账问卡上一笔底下顺手打标签的那一块。怎么用这些工具写在助手自己的提示词里（`assistant/AssistantPrompt`「记账」）。
 */
object LedgerPlugin : AssistantPlugin {

    override val id = LedgerModule.ID

    /**
     * 实际的类别表和已有的标签，每句都垫着：整理员后来建的二级、你在账单页新建的标签，对话里也认得，
     * 不再另起一个意思差不多的。只在新建二级、新建或删掉标签时变（DESIGN.md §10.1「两个模型怎么传话」）
     */
    override suspend fun background(context: Context): String {
        val store = LedgerStore.get(context.applicationContext)
        // 按名字排，不按用得多少：挂一笔就换顺序的话，前缀缓存每句都失效
        return "记账的类别表（以这份为准）：\n" + LedgerText.categoryTree(store.categories()) +
            "\n已有的标签：" + store.tagNames().joinToString("、").ifEmpty { "还没有" }
    }

    override fun tools(context: Context): List<Tool> = LedgerTools.forAssistant(LedgerStore.get(context.applicationContext))

    override fun trace(call: ToolTrace): TraceView? = LedgerTrace.of(call)

    /**
     * 每晚对账（通知上点「现在」、记账页点「现在就说」）：把没认出来的几笔列给模型，由它在对话里一笔笔问你。
     * 见 DESIGN.md §10.1 ③
     */
    override suspend fun trigger(context: Context, key: String): Trigger? {
        if (key != LedgerRoutes.CHECK.substringAfter(':')) return null
        val app = context.applicationContext
        LedgerCheck.done(app)
        return LedgerCheckPrompt.build(LedgerStore.get(app))?.let { Trigger.Turn(it) }
            ?: Trigger.Note("账都对上了，没有要问你的。")
    }

    /** 通知弹了、还没对：从桌面图标进 app，对话末尾也有一段虚线等你点。见 DESIGN.md §10.1 ③ */
    override fun pendingTrigger(context: Context): Flow<PendingTrigger?> =
        LedgerCheck.waiting(context).map { w ->
            // label 要和开场白「每晚对账：……」冒号前那段一致，点了之后换成的实线上写的就是它
            w?.let { PendingTrigger(LedgerRoutes.CHECK, "每晚对账", it.at, "有 ${it.count} 笔账没认出来，现在对一下？", "现在对") }
        }

    override suspend fun dismissTrigger(context: Context, key: String) {
        if (key == LedgerRoutes.CHECK.substringAfter(':')) LedgerCheck.done(context.applicationContext)
    }

    /**
     * 对账问卡上「#12」那一笔，交卷时挂着哪些标签：收起后接在答案后面，也告诉模型已经挂好了，
     * 它就不会再打一遍（DESIGN.md §10.4「标签怎么打」）。没挂的是 null
     */
    override suspend fun askNote(context: Context, ref: String): AskNote? {
        val id = txnIdOf(ref) ?: return null
        val tags = LedgerStore.get(context.applicationContext).txns(listOf(id)).firstOrNull()?.tags.orEmpty()
        if (tags.isEmpty()) return null
        val names = tags.joinToString("、")
        return AskNote(short = names, told = "这笔挂着标签「$names」（他在问卡上点的也在里面），已经挂好了，别再打")
    }

    /** 对账问卡上一笔底下的「＋ 打标签」（设计稿方向 B） */
    @Composable
    override fun AskAddon(ref: String) {
        txnIdOf(ref)?.let { AskTagRow(it) }
    }

    /** 问卡上模型写的「#12」→ 12。别的写法不认 */
    private fun txnIdOf(ref: String): Long? = Regex("""^#?(\d+)$""").find(ref.trim())?.groupValues?.get(1)?.toLongOrNull()
}

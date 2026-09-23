package com.abc.daodian.data.chat

import android.content.Context
import com.abc.daodian.harness.Item
import com.abc.daodian.harness.Session
import com.abc.daodian.harness.Turn
import com.abc.daodian.harness.builtin.reminder.CreateReminderTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * 对话页那段对话的存取：启动时读回来，之后 [Session] 每变一次就整轮写一次。
 *
 * 写盘排在一条单线程上：同一轮的几次覆盖、以及随后的「整轮拿掉」必须按发生的顺序落地，
 * 否则晚到的旧快照会把删掉的一轮又写回来。写盘不挂在页面上 —— 页面关了，已经发生的照样要存下。
 */
class ChatStore private constructor(private val dao: ChatDao) : Session.Listener {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** 最近 [turns] 轮，旧的在前 */
    suspend fun load(turns: Int = LOAD_TURNS): List<Turn> =
        dao.recent(turns).groupBy { it.turnId }.map { (id, rows) -> Turn(id, rows.map(::toItem)) }

    /**
     * 这条提醒当初是模型怎么算出来的（create_reminder 的 basis）。模型算歪的时候，这是唯一能看出哪步歪了的线索 ——
     * 对话里不再有卡片，它挪到编辑页。桌面速记建的、手动建的没有。
     */
    suspend fun basisOf(reminderId: Long): String? =
        dao.callArgumentsFor(CreateReminderTool.NAME, reminderId)
            ?.let(CreateReminderTool::planOf)?.basis?.takeIf { it.isNotBlank() }

    override fun changed(turn: Turn) {
        val rows = turn.items.mapIndexed { i, item -> toEntity(turn.id, i, item) }
        writes.launch { dao.replaceTurn(turn.id, rows) }
    }

    override fun discarded(turn: Turn) {
        writes.launch { dao.deleteTurn(turn.id) }
    }

    private fun toEntity(turnId: Long, seq: Int, item: Item): ChatItemEntity = when (item) {
        is Item.UserMessage -> ChatItemEntity(
            turnId = turnId, seq = seq, kind = if (item.trigger) TRIGGER else USER, text = item.text,
            atMillis = item.at.toInstant().toEpochMilli(), zoneId = item.at.zone.id
        )
        is Item.AssistantMessage -> ChatItemEntity(turnId = turnId, seq = seq, kind = ASSISTANT, text = item.text)
        is Item.ToolCall -> ChatItemEntity(
            turnId = turnId, seq = seq, kind = TOOL_CALL, text = item.arguments,
            callId = item.callId, toolName = item.name
        )
        is Item.ToolResult -> ChatItemEntity(
            turnId = turnId, seq = seq, kind = TOOL_RESULT, text = item.output,
            callId = item.callId, ok = item.ok, ref = item.ref
        )
    }

    private fun toItem(row: ChatItemEntity): Item = when (row.kind) {
        USER, TRIGGER -> Item.UserMessage(
            row.text,
            Instant.ofEpochMilli(row.atMillis ?: 0).atZone(row.zoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()),
            trigger = row.kind == TRIGGER
        )
        ASSISTANT -> Item.AssistantMessage(row.text)
        TOOL_CALL -> Item.ToolCall(row.callId.orEmpty(), row.toolName.orEmpty(), row.text)
        TOOL_RESULT -> Item.ToolResult(row.callId.orEmpty(), row.text, row.ok ?: false, row.ref)
        else -> error("认不出的对话项：${row.kind}")
    }

    companion object {
        /** 启动时读回多少轮。喂给模型的另有 ContextPolicy 裁剪，这里管的是画面上往回能翻多远 */
        const val LOAD_TURNS = 100

        private const val USER = "USER"
        /** app 自己发起的一轮（每晚对账）。列还是那几列，不用改表 */
        private const val TRIGGER = "TRIGGER"
        private const val ASSISTANT = "ASSISTANT"
        private const val TOOL_CALL = "TOOL_CALL"
        private const val TOOL_RESULT = "TOOL_RESULT"

        @Volatile private var instance: ChatStore? = null

        fun get(context: Context): ChatStore =
            instance ?: synchronized(this) {
                instance ?: ChatStore(ChatDatabase.get(context).chatDao()).also { instance = it }
            }
    }
}

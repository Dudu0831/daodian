package com.abc.daodian.harness

/**
 * 一段对话的全部轮次。[AgentLoop] 边跑边往最后一轮里追加，所以就算中途被「停」掉，
 * 已经执行过的工具调用也留在记录里 —— 提醒已经建了，模型下一轮得知道。
 *
 * 落盘不归它管：每次有变动就告诉 [listener]，由外面决定存到哪（app 里是 `data/chat/`）。
 * 通知的是整轮的快照（[Turn] 不可变），存的一方按 [Turn.id] 整轮覆盖即可，不用关心增量。
 *
 * 同一时刻只能有一个 [AgentLoop.run] 在写它。
 */
class Session(turns: List<Turn> = emptyList(), private val listener: Listener? = null) {

    interface Listener {
        /** 这一轮新开了，或者多了一项 */
        fun changed(turn: Turn)

        /** 这一轮整个拿掉了 */
        fun discarded(turn: Turn)
    }

    private val _turns = turns.toMutableList()
    private var nextId = (turns.maxOfOrNull { it.id } ?: 0L) + 1

    val turns: List<Turn> get() = _turns.toList()

    internal val current: Turn get() = _turns.last()

    internal fun begin(message: Item.UserMessage) {
        val turn = Turn(nextId++, listOf(message))
        _turns += turn
        listener?.changed(turn)
    }

    internal fun append(item: Item) {
        val turn = current.copy(items = current.items + item)
        _turns[_turns.lastIndex] = turn
        listener?.changed(turn)
    }

    /**
     * 给最后一轮里没拿到结果的调用补上结果。协议要求每个 function_call 都有对应的 output，
     * 缺一个下一次请求就会被网关拒掉。
     */
    internal fun closeDanglingCalls(output: String) {
        if (_turns.isEmpty()) return
        val answered = current.items.filterIsInstance<Item.ToolResult>().mapTo(HashSet()) { it.callId }
        current.items.filterIsInstance<Item.ToolCall>()
            .filter { it.callId !in answered }
            .forEach { append(Item.ToolResult(it.callId, output, ok = false)) }
    }

    /** 把最后一轮整轮拿掉：失败后重试、什么都没办成就被「停」掉时用，不让模型看到跑偏的痕迹 */
    fun discardLastTurn() {
        if (_turns.isEmpty()) return
        listener?.discarded(_turns.removeAt(_turns.lastIndex))
    }
}

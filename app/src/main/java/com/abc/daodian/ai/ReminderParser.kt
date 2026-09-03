package com.abc.daodian.ai

import java.time.ZonedDateTime

/**
 * 自然语言 → 结构化提醒。见 DESIGN.md §6.1
 *
 * 这层整个可以被手动编辑页替代 —— 这是设计它的前提，不是妥协。
 * 换 SDK / 换供应商只动实现类，接口不变。
 */
interface ReminderParser {
    suspend fun parse(input: String, now: ZonedDateTime, history: List<ChatTurn> = emptyList()): ParseResult
}

sealed interface ParseResult {
    data class Ok(val plan: ReminderPlan) : ParseResult
    data class NeedsClarification(val question: String, val raw: String) : ParseResult
    data class Failed(val reason: String, val cause: Throwable? = null) : ParseResult
}

/** 模型的输出。见 DESIGN.md §6.3 */
data class ReminderPlan(
    val title: String,
    val note: String? = null,
    /** ISO-8601 带偏移，如 "2026-09-02T15:00:00+08:00" */
    val firstTriggerAt: String,
    /** 模型的推算依据，如 "now + 5d, 15:00"。刻意要求它写出来，出错时能一眼看出哪儿想歪了 */
    val basis: String = "",
    val rrule: String? = null,
    val wallClockAnchored: Boolean = true,
    val confidence: Double = 1.0,
    val clarifyingQuestion: String? = null
)

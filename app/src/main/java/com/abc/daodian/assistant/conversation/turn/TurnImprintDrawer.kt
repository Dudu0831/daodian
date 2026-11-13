package com.abc.daodian.assistant.conversation.turn

import com.abc.daodian.assistant.conversation.ask.answerLabelOf
import com.abc.daodian.assistant.conversation.AskState
import com.abc.daodian.assistant.conversation.TurnBlock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.abc.daodian.assistant.plugin.TraceState
import com.abc.daodian.assistant.plugin.TraceView
import com.abc.daodian.runtime.ask.AskRequest
import com.abc.daodian.runtime.ask.AskUserTool
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianPalette

/**
 * 点开伴随印后，在「· 到点」下方展开的轻量细目抽屉：左边一道 1.5dp 细墨线，逐条列出答复与写操作。
 * 带 [TraceView.route] 的行整行可点，末尾带 `›`。
 */
@Composable
internal fun TurnImprintDrawer(
    sealedAsks: List<TurnBlock.Ask>,
    traces: List<TurnBlock.Trace>,
    onOpen: (String) -> Unit
) {
    val colors = DaodianColors.current
    Column(
        Modifier
            .drawBehind {
                drawLine(
                    colors.rule2,
                    Offset(0.75.dp.toPx(), 2.dp.toPx()),
                    Offset(0.75.dp.toPx(), size.height - 2.dp.toPx()),
                    1.5.dp.toPx(),
                    StrokeCap.Round
                )
            }
            .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        sealedAsks.forEach { ask ->
            val req = remember(ask.request, ask.arguments) { ask.request ?: AskUserTool.draftOf(ask.arguments) }
            val label = when (ask.state) {
                AskState.ANSWERED -> "答了"
                AskState.SAID -> "问过"
                else -> "没答"
            }
            val labelTone = if (ask.state == AskState.ANSWERED) colors.accent else colors.muted
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = DrawerLabelStyle, color = labelTone, modifier = Modifier.widthIn(min = 34.dp))
                Text(drawerAskText(ask, req, colors), style = DrawerBodyStyle, modifier = Modifier.weight(1f, fill = false))
            }
        }

        traces.forEach { trace ->
            val view = remember(trace.state, trace.arguments, trace.output, trace.ref) { traceViewOf(trace) }
            val failed = trace.state == TraceState.FAILED
            val running = trace.state == TraceState.RUNNING
            val route = view.route?.takeIf { trace.state == TraceState.OK }
            val labelTone = when {
                failed -> colors.red
                running -> colors.muted
                else -> colors.accent
            }
            val bodyTone = if (failed) colors.red else colors.ink2

            Row(
                Modifier.clickable(enabled = route != null) { route?.let(onOpen) },
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (running) view.working else view.settled,
                    style = DrawerLabelStyle,
                    color = labelTone,
                    modifier = Modifier.widthIn(min = 34.dp)
                )
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (view.lines.isNotEmpty()) {
                        view.lines.forEach { line ->
                            Text(line, style = DrawerBodyStyle, color = bodyTone)
                        }
                    } else {
                        val content = view.text.ifBlank { if (running) "…" else view.settled }
                        Text(
                            buildAnnotatedString {
                                append(content)
                                if (route != null) {
                                    withStyle(SpanStyle(color = colors.hint)) { append(" ›") }
                                }
                            },
                            style = DrawerBodyStyle,
                            color = bodyTone
                        )
                    }
                }
            }
        }
    }
}

/** 抽屉里一行展示的问卡答复细目 */
private fun drawerAskText(ask: TurnBlock.Ask, req: AskRequest, colors: DaodianPalette) = buildAnnotatedString {
    when (ask.state) {
        AskState.SAID -> {
            val asked = req.questions.mapNotNull { it.prompt ?: it.amount ?: it.context }.joinToString("、").trimEnd('？', '?')
            withStyle(SpanStyle(color = colors.muted)) {
                append(if (asked.isNotEmpty()) "$asked · 你直接说了" else "你直接说了")
            }
        }
        AskState.UNANSWERED -> {
            val asked = req.questions.mapNotNull { it.prompt ?: it.amount ?: it.context }.joinToString("、").trimEnd('？', '?')
            withStyle(SpanStyle(color = colors.muted)) {
                append(if (asked.isNotEmpty()) "$asked · 没答" else "没答")
            }
        }
        else -> {
            req.questions.forEachIndexed { i, q ->
                if (i > 0) withStyle(SpanStyle(color = colors.rule2)) { append("  ·  ") }
                val ans = answerLabelOf(q, ask.picks.getOrNull(i), req.single)
                val note = ask.notes.getOrNull(i)?.short
                val amount = q.amount
                if (amount != null) {
                    withStyle(SpanStyle(color = colors.muted)) { append("$amount ") }
                    withStyle(SpanStyle(color = if (ans == null) colors.muted else colors.ink2)) { append(ans ?: "先放着") }
                    if (note != null) withStyle(SpanStyle(color = colors.muted)) { append("($note)") }
                } else {
                    val prompt = (q.prompt ?: q.context).orEmpty().trimEnd('？', '?')
                    if (prompt.isNotEmpty()) withStyle(SpanStyle(color = colors.muted)) { append("$prompt ") }
                    withStyle(SpanStyle(color = if (ans == null) colors.muted else colors.ink2)) { append(ans ?: "先放着") }
                    if (note != null) withStyle(SpanStyle(color = colors.muted)) { append(" · $note") }
                }
            }
        }
    }
}

package com.abc.daodian.agent.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abc.daodian.agent.feature.DraftArgs
import com.abc.daodian.ledger.domain.Money
import com.abc.daodian.ledger.tools.AddCategoryTool
import com.abc.daodian.ledger.tools.AddExpenseTool
import com.abc.daodian.ledger.tools.UpdateExpensesTool
import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.tools.CreateReminderTool
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/*
 * 痕：一次写操作在对话里留下的一行小字。见 DESIGN.md §6.9，动效稿「动手」「办成」「没办成」三拍。
 *
 * 它是代码按工具结果画的，不是模型说的 —— 模型嘴上说「记下了」却没调工具，这里就是空的。
 * 所以它不能省：真机上出过只回一句「明白」、什么都没建的情形。
 */

/** 点痕去哪 */
sealed interface TraceTarget {
    data class Reminder(val id: Long) : TraceTarget
    data class Txn(val id: Long) : TraceTarget
}

/** 痕上要写的字，从工具名 + 参数 + 结果推出来。在建、办成、没办成三种状态用同一份规则，重建历史也用它 */
data class TraceView(
    /** 在办时的标签：「在记提醒」 */
    val working: String,
    /** 办成 / 没办成的标签：「提醒」「没建成」 */
    val settled: String,
    val text: String,
    /** 一次改了好几笔：点开逐笔看 */
    val lines: List<String> = emptyList(),
    val target: TraceTarget? = null
)

private val mapper = ObjectMapper()
private fun JsonNode.s(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

/** 结果的第一行去掉工具自己的前缀（「没建。」「没记。」），再截到第一句 */
private fun reasonOf(output: String?, vararg prefixes: String): String {
    var s = output?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    prefixes.forEach { s = s.removePrefix(it).trim() }
    // 模型自己把参数写坏了：原因里贴着半截 JSON，给人看没意义
    if (s.startsWith("参数不是合法的 JSON")) return "参数没写对"
    return s.substringBefore("照这个意思").substringBefore('。').trim().ifEmpty { "没办成" }
}

fun traceViewOf(block: TurnBlock.Trace): TraceView {
    val args = runCatching { mapper.readTree(block.arguments) }.getOrNull()
    val ok = block.state == TraceState.OK
    val failed = block.state == TraceState.FAILED
    return when (block.tool) {
        CreateReminderTool.NAME -> {
            val plan = if (ok) CreateReminderTool.planOf(block.arguments) else null
            TraceView(
                working = "在记提醒",
                settled = if (failed) "没建成" else "提醒",
                text = when {
                    failed -> reasonOf(block.output, "没建。")
                    plan != null -> "${whenOf(plan)} · ${plan.title}"
                    else -> DraftArgs.partialText(block.arguments, "title").orEmpty()
                },
                target = block.ref?.takeIf { ok }?.let { TraceTarget.Reminder(it) }
            )
        }
        AddExpenseTool.NAME -> TraceView(
            working = "在记账",
            settled = if (failed) "没记成" else "记账",
            text = when {
                failed -> reasonOf(block.output, "没记。")
                args != null -> listOfNotNull(
                    args.s("summary"),
                    Money.parseCents(args.s("amount"))?.let { "¥" + Money.yuan(it) },
                ).joinToString(" ") + if (ok) " · " + (args.s("category") ?: "未归类") else ""
                else -> DraftArgs.partialText(block.arguments, "summary").orEmpty()
            },
            target = block.ref?.takeIf { ok }?.let { TraceTarget.Txn(it) }
        )
        UpdateExpensesTool.NAME -> {
            // 结果第一行：「改好了 3 笔：午饭 36.50 → 餐饮/堂食；…」
            val head = block.output?.lineSequence()?.firstOrNull().orEmpty()
            val lines = head.substringAfter('：', "").split('；').map { it.trim() }.filter { it.isNotEmpty() }
            TraceView(
                working = "在改账",
                settled = if (failed) "没改成" else "记账",
                text = when {
                    failed -> head.ifEmpty { "没改成" }
                    !ok -> ""
                    lines.size == 1 -> lines.single()
                    else -> "改了 ${lines.size} 笔"
                },
                lines = if (ok && lines.size > 1) lines else emptyList(),
                target = block.ref?.takeIf { ok && lines.size == 1 }?.let { TraceTarget.Txn(it) }
            )
        }
        AddCategoryTool.NAME -> TraceView(
            working = "在加类别",
            settled = if (failed) "没加成" else "类别",
            text = when {
                failed -> reasonOf(block.output, "没建。")
                ok -> block.output?.lineSequence()?.firstOrNull()?.removePrefix("建好了：").orEmpty()
                else -> listOfNotNull(args?.s("parent"), args?.s("name")).joinToString("/")
            }
        )
        else -> TraceView("在办", if (failed) "没办成" else "办了", block.tool)
    }
}

/**
 * 什么时候响，写人话。重复的报「每天 08:00」，一次性的报完整日期；
 * 当天事项没有钟点：「9月18日 周五 · 今天之内」/「每天 · 当天之内」
 */
private fun whenOf(plan: ReminderPlan): String {
    val rrule = Format.humanRrule(plan.rrule)
    val dueDay = if (plan.allDay) runCatching { PlanValidator.dueDayOf(plan) }.getOrNull() else null
    val millis = runCatching { PlanValidator.triggerMillis(plan) }.getOrNull()
    return when {
        dueDay != null && rrule != null -> "$rrule · 当天之内"
        // 离得远的，dayTaskWhen 给的是「9月30日之内」—— 前面已经写了日期，不再说一遍
        dueDay != null -> "${Format.humanDay(dueDay)} · " +
            Format.dayTaskWhen(dueDay).let { if (it.startsWith("${dueDay.monthValue}月")) "当天之内" else it }
        millis == null -> Format.humanDateTime(plan.firstTriggerAt)
        rrule != null -> "$rrule ${Format.clock(millis)}"
        else -> Format.humanDateTime(millis)
    }
}

/** 对勾只在刚办成时描一次；列表滚回来、重启读回来的都直接画好 */
private fun isFresh(at: Long) = System.currentTimeMillis() - at < 1_500

@Composable
fun TraceLine(block: TurnBlock.Trace, onOpen: (TraceTarget) -> Unit, onToggle: () -> Unit) {
    val colors = DaodianColors.current
    val view = remember(block.state, block.arguments, block.output, block.ref) { traceViewOf(block) }
    val expandable = view.lines.isNotEmpty()
    val tappable = block.state == TraceState.OK && (expandable || view.target != null)
    val failed = block.state == TraceState.FAILED
    val tone by animateColorAsState(if (failed) colors.red else colors.ink2, Motion.flow(Motion.SHORT), label = "traceTone")
    val labelTone by animateColorAsState(if (failed) colors.red else colors.muted, Motion.flow(Motion.SHORT), label = "traceLabel")

    Column {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .clickable(enabled = tappable) { if (expandable) onToggle() else view.target?.let(onOpen) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TraceMark(block.state, fresh = isFresh(block.doneAt))
            // 「在记提醒」比「提醒」宽：宽度跟着收，不然换完字中间空一截
            Crossfade(
                if (block.state == TraceState.RUNNING) view.working else view.settled,
                modifier = Modifier.animateContentSize(Motion.flow()),
                animationSpec = tween(Motion.SHORT),
                label = "traceKind"
            ) {
                Text(it, style = DaodianType.speakerTag.copy(fontSize = 11.sp, letterSpacing = 0.2.em), color = labelTone)
            }
            Crossfade(block.state == TraceState.RUNNING, animationSpec = tween(120), label = "traceText", modifier = Modifier.weight(1f, fill = false)) { running ->
                if (running) {
                    InkText(view.text, streaming = true, style = DaodianType.bodySmall, color = tone, caret = false)
                } else {
                    Text(view.text, style = DaodianType.bodySmall, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (tappable) {
                val turn by animateFloatAsState(if (expandable) (if (block.expanded) 270f else 90f) else 0f, Motion.flow(), label = "traceChevron")
                ChevronRightIcon(Modifier.graphicsLayer { rotationZ = turn }, size = 12.dp, tint = colors.hint)
            }
        }
        AnimatedVisibility(
            visible = expandable && block.expanded,
            enter = expandVertically(Motion.settle()) + fadeIn(Motion.flow()),
            exit = shrinkVertically(Motion.flow()) + fadeOut(Motion.exit())
        ) {
            Column(Modifier.padding(start = 22.dp, bottom = 4.dp)) {
                view.lines.forEach { Text(it, style = DaodianType.bodySmall.copy(lineHeight = 22.sp), color = colors.ink2) }
            }
        }
    }
}

/**
 * 痕最左边那个记号：在办时是朱砂圆点外一圈呼吸的晕；办成了圆点缩掉、对勾一笔描出来；没办成是红色 ×。
 * 晕是无限循环的动画，只在「在办」时进组合 —— 挂着不用也会一直要帧。
 */
@Composable
private fun TraceMark(state: TraceState, fresh: Boolean) {
    val colors = DaodianColors.current
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = state == TraceState.RUNNING,
            enter = fadeIn(Motion.flow()),
            exit = scaleOut(Motion.exit()) + fadeOut(Motion.exit())
        ) {
            val halo by rememberInfiniteTransition(label = "halo").animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
                label = "haloP"
            )
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(14.dp)
                        .graphicsLayer {
                            val s = 0.55f + 0.45f * halo
                            scaleX = s
                            scaleY = s
                            alpha = 0.3f - 0.2f * halo
                        }
                        .background(colors.accent, CircleShape)
                )
                Box(Modifier.size(6.dp).background(colors.accent, CircleShape))
            }
        }
        if (state == TraceState.OK) {
            val draw = remember { Animatable(if (fresh) 0f else 1f) }
            LaunchedEffect(Unit) { if (fresh) draw.animateTo(1f, Motion.settle(Motion.MID, delay = 80)) }
            val tint = colors.accent
            Canvas(Modifier.size(14.dp)) {
                val u = size.width / 24f
                val path = Path().apply {
                    moveTo(4f * u, 12.5f * u)
                    lineTo(9f * u, 17.5f * u)
                    lineTo(20f * u, 6.5f * u)
                }
                val measure = PathMeasure().apply { setPath(path, false) }
                val part = Path()
                measure.getSegment(0f, measure.length * draw.value, part, true)
                drawPath(part, tint, style = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (state == TraceState.FAILED) {
            val tint = colors.red
            Canvas(Modifier.size(14.dp)) {
                val u = size.width / 24f
                val w = 2.2f * u
                drawLine(tint, Offset(6 * u, 6 * u), Offset(18 * u, 18 * u), w, StrokeCap.Round)
                drawLine(tint, Offset(18 * u, 6 * u), Offset(6 * u, 18 * u), w, StrokeCap.Round)
            }
        }
    }
}

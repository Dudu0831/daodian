package com.abc.daodian.assistant.conversation.ask

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.assistant.plugin.AssistantPlugin
import com.abc.daodian.assistant.plugin.AssistantPlugins
import com.abc.daodian.runtime.ask.AskQuestion
import com.abc.daodian.runtime.ask.Pick
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import com.abc.daodian.shared.ui.InkChip

/** 问句的字：宋体 17 */
private val HeadPrompt = DaodianType.cardTitle.copy(fontSize = 17.sp, lineHeight = 25.sp)

private val HintText = DaodianType.caption.copy(fontSize = 12.sp, lineHeight = 17.sp)

private val AmountText = DaodianType.rowTitle.copy(fontSize = 22.sp, lineHeight = 28.sp)

/**
 * 卡里的一题：问账的金额打头（宋体 22）、后面是出处；别的是出处 + 问句。底下依据、猜测、模块挂的那一块。
 * [glyphRoom]：右上角盖着「问」印，字给它让出一截
 */
@Composable
internal fun Question(
    q: AskQuestion,
    pick: Pick?,
    live: Boolean,
    glyphRoom: Boolean = false,
    onPick: (Int) -> Unit,
    onRetype: (String) -> Unit
) {
    val colors = DaodianColors.current
    // 问卡的类在 :runtime 里，跨模块的属性不能 smart cast，先取出来
    val amount = q.amount
    val origin = q.context
    val prompt = q.prompt
    val hint = q.hint
    val ref = q.ref
    Column {
        Column(Modifier.padding(end = if (glyphRoom) 28.dp else 0.dp)) {
            if (amount != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(amount, style = AmountText, color = colors.ink, modifier = Modifier.alignByBaseline())
                    Text(
                        origin.orEmpty(), style = DaodianType.caption, color = colors.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).alignByBaseline()
                    )
                }
                if (prompt != null) Text(prompt, style = DaodianType.bodySmall.copy(fontSize = 14.sp), color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
            } else {
                if (origin != null) Text(origin, style = DaodianType.caption, color = colors.muted)
                if (prompt != null) Text(prompt, style = HeadPrompt, color = colors.ink)
            }
            if (hint != null) Text(hint, style = HintText, color = colors.muted, modifier = Modifier.padding(top = 3.dp))
        }
        Guesses(q, pick, live, onPick, onRetype)
        if (live && ref != null) Addon(ref)
    }
}

/**
 * 一题的猜测。都不是的话在底下输入条里写（或按住说），发出去落成排在最前的一颗实心胶囊（11-12，
 * 以前这里有一颗虚线「其他…」，点了原地长出输入框）；点那颗 = 取下来，字交回输入条接着改
 */
@Composable
private fun Guesses(q: AskQuestion, pick: Pick?, live: Boolean, onPick: (Int) -> Unit, onRetype: (String) -> Unit) {
    // 参数还在流：猜测先半透明，点不了 —— 可能还在变
    val ready by animateFloatAsState(if (live) 1f else 0.55f, Motion.flow(), label = "askReady")
    val selected = (pick as? Pick.Option)?.index
    val typed = (pick as? Pick.Typed)?.text
    if (q.options.isEmpty() && typed == null) return
    Box(Modifier.padding(top = 11.dp).graphicsLayer { alpha = ready }) {
        ChipRows {
            // 自己写的排最前：猜测多到要横滑时，排在最后就在屏幕外了
            if (typed != null) ChipLabel(typed, selected = true, dimmed = false, enabled = live) { onRetype(typed) }
            q.options.forEachIndexed { j, o ->
                ChipLabel(
                    o.label, detail = o.detail,
                    selected = selected == j, dimmed = pick != null && selected != j, enabled = live
                ) { onPick(j) }
            }
        }
    }
}

private val CHIP_HEIGHT = 34.dp
private val CHIP_GAP = 7.dp

/** 猜测排几行：固定一行，装不下横着滑（11-12 先做过「最多两行」，这题两行下一题一行，换题时卡一高一矮） */
private const val CHIP_ROWS = 1

/**
 * 一排猜测：[CHIP_ROWS] 行装得下就这么排；装不下（猜测多、字长）就匀成 [CHIP_ROWS] 行横着滑，
 * 还有没露出来的那一侧边上淡出去。一颗最宽占八成半，再长截断 —— 不然一颗就占满一屏，后面的看不出能滑
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun ChipRows(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val viewport = constraints.maxWidth
        val gap = with(LocalDensity.current) { CHIP_GAP.roundToPx() }
        val scroll = rememberScrollState()
        // 装得下就不接手势：这一排上的左右滑留给翻页
        Layout(content, Modifier.edgeFade(scroll).horizontalScroll(scroll, enabled = scroll.maxValue > 0)) { measurables, _ ->
            val chips = measurables.map { it.measure(Constraints(maxWidth = (viewport * 0.85f).toInt())) }
            fun widthOf(row: List<Placeable>) = row.sumOf { it.width } + gap * (row.size - 1).coerceAtLeast(0)
            val wrapped = mutableListOf(mutableListOf<Placeable>())
            chips.forEach { c ->
                val row = wrapped.last()
                if (row.isNotEmpty() && widthOf(row) + gap + c.width > viewport) wrapped += mutableListOf(c) else row += c
            }
            val rows: List<List<Placeable>> = (if (wrapped.size <= CHIP_ROWS) wrapped else {
                // 按宽度匀开：每行攒到平均宽度就换行，最后一行兜底
                val target = widthOf(chips) / CHIP_ROWS
                val even = mutableListOf(mutableListOf<Placeable>())
                chips.forEach { c ->
                    if (even.size < CHIP_ROWS && even.last().isNotEmpty() && widthOf(even.last()) >= target) even += mutableListOf(c)
                    else even.last() += c
                }
                even
            }).filter { it.isNotEmpty() }
            val rowHeight = chips.maxOfOrNull { it.height } ?: 0
            val width = maxOf(viewport, rows.maxOfOrNull(::widthOf) ?: 0)
            val height = rows.size * rowHeight + gap * (rows.size - 1).coerceAtLeast(0)
            layout(width, height) {
                rows.forEachIndexed { r, row ->
                    var x = 0
                    row.forEach {
                        it.placeRelative(x, r * (rowHeight + gap))
                        x += it.width + gap
                    }
                }
            }
        }
    }
}

/** 横滑的那一排：还能往哪边滑，哪边的边上就淡出去 */
private fun Modifier.edgeFade(scroll: ScrollState, width: Dp = 22.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = width.toPx()
        if (scroll.canScrollForward) drawRect(
            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
            topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), blendMode = BlendMode.DstIn
        )
        if (scroll.canScrollBackward) drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w),
            size = Size(w, size.height), blendMode = BlendMode.DstIn
        )
    }

/** 模块在这题底下挂的一块（记账：顺手打标签），见 [AssistantPlugin.AskAddon]。不是它的 ref 就什么都不画 */
@Composable
private fun Addon(ref: String) {
    val plugins = remember { AssistantPlugins.plugins }
    plugins.forEach { it.AskAddon(ref) }
}

@Composable
private fun ChipLabel(
    text: String,
    selected: Boolean,
    dimmed: Boolean,
    enabled: Boolean,
    detail: String? = null,
    onClick: () -> Unit
) {
    InkChip(
        selected = selected, dimmed = dimmed, enabled = enabled,
        shape = RoundedCornerShape(CHIP_HEIGHT / 2),
        modifier = Modifier.height(CHIP_HEIGHT),
        onClick = onClick
    ) { onInk ->
        Row(
            Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text,
                style = DaodianType.bodySmall.copy(fontSize = 14.sp),
                color = onInk.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (detail != null) Text(detail, style = DaodianType.caption, color = onInk.sub, maxLines = 1)
        }
    }
}

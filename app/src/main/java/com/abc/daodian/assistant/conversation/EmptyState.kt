package com.abc.daodian.assistant.conversation

import com.abc.daodian.assistant.conversation.turn.PendingTriggerBlock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.assistant.plugin.AssistantPlugins
import com.abc.daodian.assistant.plugin.PendingTrigger
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import java.time.LocalTime

/** 例句前的序号 */
private val ordinals = listOf("一", "二", "三", "四", "五", "六", "七", "八")

/**
 * 空状态。招呼语跟时段走，底下四条例句是「这个 app 怎么用」的全部说明书 ——
 * 点一下就直接发出去，不用先学语法。
 * 有等着你点的一轮（[pending]）时，那段虚线压在最底下、贴着输入框，和有对话时的位置一样。
 */
@Composable
internal fun EmptyState(
    onPickExample: (String) -> Unit,
    pending: PendingTrigger?,
    onGo: (PendingTrigger) -> Unit,
    onDismiss: (PendingTrigger) -> Unit
) {
    val colors = DaodianColors.current
    val greeting = remember { Format.greeting(LocalTime.now().hour) }

    // 键盘弹起时高度会砍掉一半，不给滚动的话例句会被裁掉两条。
    // 内容至少撑满一屏，虚线段前面那个 weight 才有空可占，把它推到底；键盘弹起、放不下时 weight 缩成 0，整页照常滚
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(96.dp))
            Text(greeting, style = DaodianType.greetingSoft, color = colors.muted)
            Text("有什么要记着的？", style = DaodianType.greeting, color = colors.ink)

            Spacer(Modifier.height(52.dp))
            Text("这样说就行", style = DaodianType.sectionLabel, color = colors.muted)
            Spacer(Modifier.height(6.dp))

            AssistantPlugins.examples.zip(ordinals).forEach { (prompt, ordinal) ->
                HorizontalDivider(color = colors.rule)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPickExample(prompt) }
                        .padding(vertical = 15.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text(ordinal, style = DaodianType.ordinal, color = colors.hint)
                    Text(prompt, style = DaodianType.body, color = colors.ink2)
                }
            }
            HorizontalDivider(color = colors.rule)
            Spacer(Modifier.height(24.dp))

            Spacer(Modifier.weight(1f))
            androidx.compose.animation.AnimatedVisibility(
                visible = pending != null,
                enter = fadeIn(Motion.flow()),
                exit = fadeOut(Motion.exit())
            ) {
                // 退场那几帧 pending 已经是 null 了，用最后一次的画完
                val last = remember { arrayOfNulls<PendingTrigger>(1) }
                pending?.let { last[0] = it }
                last[0]?.let { p ->
                    Box(Modifier.padding(bottom = 16.dp)) {
                        PendingTriggerBlock(p, onGo = { onGo(p) }, onDismiss = { onDismiss(p) })
                    }
                }
            }
        }
    }
}

package com.abc.daodian.reminder.presentation.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType

/*
 * 提醒设置页「派活」那一组里，点「听谁」「暗号」弹的两个框。和记账设置页点「整理间隔」弹框一个路数：
 * 改完点确定才存，取消不动。
 */

/** 听谁：填她在通知上的名字；最近发过消息的几个名字点一下填进去 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelayWhoDialog(current: String, seen: List<String>, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("听谁") },
        text = {
            Column {
                RelayField(draft, "她在通知上的名字（微信备注名）") { draft = it }
                Spacer(Modifier.height(10.dp))
                if (seen.isNotEmpty()) {
                    RelayNote("最近发过消息的，点一下填进去：")
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        seen.forEach { name -> Chip(name, on = name == draft) { draft = name } }
                    }
                } else {
                    RelayNote("空着就谁都不听。还没见过谁发来的消息 —— 给派活勾上微信（设置 → 权限与监听 → 谁在听），她再发一句，这里就有名字可挑。")
                }
            }
        }
    )
}

/** 暗号：设了只接这几个字开头的，空着句句都交给模型 */
@Composable
fun RelayCodeDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("暗号") },
        text = {
            Column {
                RelayField(draft, "比如「到点」") { draft = it }
                Spacer(Modifier.height(10.dp))
                RelayNote(
                    if (draft.isBlank()) "空着：她发的每一句都交给模型，模型看是不是要你做的事。"
                    else "只接「${draft.trim()}」开头的，比如「${draft.trim()} 明天下午三点取快递」。别的只记下，不发给模型。"
                )
            }
        }
    )
}

/** 一行输入框：纸底、细边框 */
@Composable
internal fun RelayField(value: String, placeholder: String, onChange: (String) -> Unit) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(5.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = DaodianType.body.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.ink),
        modifier = Modifier.fillMaxWidth().height(44.dp),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().background(colors.paper, shape).border(1.dp, colors.rule, shape).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) Text(placeholder, style = DaodianType.body, color = colors.hint, maxLines = 1)
                inner()
            }
        }
    )
}

@Composable
internal fun RelayNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DaodianType.settingNote, color = DaodianColors.current.muted, modifier = modifier)
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = DaodianType.caption,
        color = if (on) colors.accent else colors.ink2,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, if (on) colors.accent else colors.rule2, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

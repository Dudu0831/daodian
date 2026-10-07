package com.abc.daodian.reminder.presentation.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import kotlinx.coroutines.launch

/*
 * 派活的三个框：提醒设置页「加一个人」从底下弹出来的纸，某个人那一页上点「名字」「暗号」弹的两个框。
 * 框和记账设置页点「整理间隔」一个路数：改完点确定才存，取消不动。
 */

/**
 * 加一个人的底纸（和记忆页「记一条」那张一个样子）：名字、最近发过消息的名字点一下填进去、暗号（可以空着）。
 * 重名不关纸，原因写在名字底下
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RelayAddSheet(seen: List<String>, onAdd: suspend (name: String, code: String) -> String?, onDismiss: () -> Unit) {
    val colors = DaodianColors.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = colors.surface,
        scrimColor = colors.ink.copy(alpha = 0.32f),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 18.dp).size(36.dp, 4.dp)
                    .background(colors.rule, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 20.dp)
        ) {
            Text("加一个人", style = DaodianType.rowTitle.copy(fontSize = 17.sp), color = colors.ink)
            Spacer(Modifier.height(16.dp))

            FieldLabel("名字")
            RelayField(name, "通知上的名字，微信是备注名") { name = it; problem = null }
            problem?.let {
                Text(it, style = DaodianType.caption, color = colors.red, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp))
            }
            if (seen.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                RelayNote("最近发过消息的，点一下填进去：", Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    seen.forEach { s -> Chip(s, on = s == name) { name = s; problem = null } }
                }
            }
            Spacer(Modifier.height(18.dp))

            FieldLabel("暗号")
            RelayField(code, "可以空着") { code = it }
            Spacer(Modifier.height(8.dp))
            RelayNote(codeNote(code, "这个人"), Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                val ready = name.isNotBlank()
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (ready) colors.solid else colors.rule2)
                        .clickable(enabled = ready) {
                            scope.launch {
                                val p = onAdd(name, code)
                                if (p == null) scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } else problem = p
                            }
                        }
                        .padding(horizontal = 30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("加上", style = DaodianType.button, color = colors.onSolid)
                }
            }
        }
    }
}

/** 改名字：通知上的名字；最近发过消息的几个名字点一下填进去。重名不关框，原因写在底下 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelayNameDialog(current: String, seen: List<String>, onSave: suspend (String) -> String?, onDismiss: () -> Unit) {
    val colors = DaodianColors.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf(current) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { scope.launch { problem = onSave(draft) } }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("名字") },
        text = {
            Column {
                RelayField(draft, "通知上的名字，微信是备注名") { draft = it; problem = null }
                Spacer(Modifier.height(10.dp))
                val p = problem
                when {
                    p != null -> Text(p, style = DaodianType.settingNote, color = colors.red)
                    seen.isNotEmpty() -> {
                        RelayNote("要和通知标题一字不差。最近发过消息的，点一下填进去：")
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            seen.forEach { name -> Chip(name, on = name == draft) { draft = name } }
                        }
                    }
                    else -> RelayNote("要和通知标题一字不差。发来过的记录跟着改名，还算这个人的。")
                }
            }
        }
    )
}

/** 暗号：设了只接这几个字开头的，空着句句都交给模型 */
@Composable
fun RelayCodeDialog(who: String, current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
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
                RelayNote(codeNote(draft, who))
            }
        }
    )
}

private fun codeNote(code: String, who: String): String =
    if (code.isBlank()) "空着：${who}发的每一句都交给模型，模型看是不是要你做的事。"
    else "只接「${code.trim()}」开头的，比如「${code.trim()} 明天下午三点取快递」。别的只记下，不发给模型。"

/** 输入框上面的小标签，和组标签一个字 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text, style = DaodianType.sectionLabel, color = DaodianColors.current.muted,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
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

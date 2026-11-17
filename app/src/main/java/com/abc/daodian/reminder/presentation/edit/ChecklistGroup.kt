package com.abc.daodian.reminder.presentation.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.abc.daodian.reminder.domain.Checklist
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.BoxIcon
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.SwipeToDelete

/** 编辑页清单的一行。[key] 是库里的 id；新建还没记下的是负数 */
data class CheckLine(val key: Long, val text: String, val done: Boolean)

/**
 * 编辑页的清单（§4.5，设计稿 https://claude.ai/artifact/KUbyGsiU6L1jNMtgHjMGXA 方向甲）：放在标题和复述底下、所有设置上面。
 * 没有清单时只一行淡的「＋ 清单」，点了才出这一组。
 *
 * 点方框勾 / 取消，勾了的变灰划线、**留在原地不往下沉**（免得手指底下的行跳走）；点字就地改，左滑删（撤销由页面给）；
 * 最后一行「加一样」，回车加上、光标留在这一行接着打下一样。组名右边「还剩 5 样」/「都勾完了」。
 * 存不存由调用的一方定：改已有的当场存，新建的跟着「记下」。
 */
@Composable
fun ChecklistGroup(
    lines: List<CheckLine>,
    onAdd: (String) -> Unit,
    onToggle: (CheckLine) -> Unit,
    onRename: (CheckLine, String) -> Unit,
    onRemove: (CheckLine) -> Unit,
    beforeEdit: () -> Unit = {}
) {
    val colors = DaodianColors.current
    var opened by rememberSaveable { mutableStateOf(false) }
    val addFocus = remember { FocusRequester() }
    var wantFocus by remember { mutableStateOf(false) }

    if (lines.isEmpty() && !opened) {
        Text(
            "＋ 清单", style = DaodianType.bodySmall, color = colors.hint,
            modifier = Modifier
                .padding(top = 14.dp)
                .clickable { beforeEdit(); opened = true; wantFocus = true }
                .padding(horizontal = 4.dp, vertical = 8.dp)
        )
        return
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text("清单", style = DaodianType.sectionLabel, color = colors.muted, modifier = Modifier.weight(1f))
        val left = lines.count { !it.done }
        if (lines.isNotEmpty()) {
            Text(if (left == 0) "都勾完了" else "还剩 $left 样", style = DaodianType.caption, color = colors.ink2)
        }
    }
    PaperGroup {
        lines.forEachIndexed { i, line ->
            if (i > 0) GroupRule()
            // 按 key 记住滑动的状态：不然删掉一行后，下面那一行接过「已经滑走」的状态，只剩一条「删除」底色
            key(line.key) {
                SwipeToDelete(onDelete = { onRemove(line) }, rowColor = colors.surface) {
                    ItemRow(line, onToggle = { onToggle(line) }, onRename = { onRename(line, it) })
                }
            }
        }
        if (lines.size < Checklist.MAX_ITEMS) {
            if (lines.isNotEmpty()) GroupRule()
            AddRow(focus = addFocus, onAdd = onAdd)
        }
    }
    LaunchedEffect(wantFocus) {
        if (wantFocus) { addFocus.requestFocus(); wantFocus = false }
    }
}

/** 一样：方框 + 就地改的字。字在失焦、按回车、离开页面时存 */
@Composable
private fun ItemRow(line: CheckLine, onToggle: () -> Unit, onRename: (String) -> Unit) {
    val colors = DaodianColors.current
    var text by remember(line.key) { mutableStateOf(line.text) }
    // 别处改了（对话里、勾完回来）而这一行没在改：跟上库里的字
    var editing by remember(line.key) { mutableStateOf(false) }
    if (!editing && text != line.text) text = line.text
    val save by rememberUpdatedState { if (text != line.text) onRename(text) }
    DisposableEffect(line.key) { onDispose { save() } }

    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.clickable(onClick = onToggle).padding(start = 18.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            BoxIcon(size = 18.dp, tint = if (line.done) colors.hint else colors.ink2, checked = line.done, mark = colors.surface, strokeWidth = 1.3.dp)
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(Checklist.MAX_CHARS) },
            singleLine = true,
            textStyle = DaodianType.body.copy(
                color = if (line.done) colors.hint else colors.ink,
                textDecoration = if (line.done) TextDecoration.LineThrough else null
            ),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save(); editing = false }),
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp, top = 12.dp, bottom = 12.dp)
                .onFocusChanged { f ->
                    if (editing && !f.isFocused) save()
                    editing = f.isFocused
                }
        )
    }
}

/** 最后一行「加一样」：回车加上，框清空、光标留着接着打 */
@Composable
private fun AddRow(focus: FocusRequester, onAdd: (String) -> Unit) {
    val colors = DaodianColors.current
    var text by rememberSaveable { mutableStateOf("") }
    fun commit() {
        Checklist.clean(text)?.let(onAdd)
        text = ""
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable { focus.requestFocus() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        BoxIcon(
            Modifier.padding(start = 18.dp, end = 12.dp).size(18.dp), size = 18.dp,
            tint = colors.rule2, dashed = true, strokeWidth = 1.3.dp
        )
        Box(Modifier.weight(1f).padding(end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            if (text.isEmpty()) Text("加一样", style = DaodianType.body, color = colors.hint)
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(Checklist.MAX_CHARS) },
                singleLine = true,
                textStyle = DaodianType.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { commit() }, onDone = { commit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    // 打了字没按回车就点别处：也算加上，别把打好的字丢了
                    .onFocusChanged { if (!it.isFocused && text.isNotBlank()) commit() }
            )
        }
    }
}

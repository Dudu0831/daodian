package com.abc.daodian.agent.memory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.agent.memory.Memory
import com.abc.daodian.agent.memory.MemoryRules
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.IconTapTarget
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.PlusIcon
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SwipeToDelete
import com.abc.daodian.shared.ui.UndoBar
import com.abc.daodian.shared.ui.activityViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 记忆管理页：它记着的每一条，点一条从底下弹出来改，左滑删（5 秒撤销），右上角「+」自己写一条。见 DESIGN.md §6.9
 *
 * 版式和设置页一样（一组一张纸），设计稿第二版方向 A：https://claude.ai/artifact/Wo6Je3fueZ99dSj5Vag7qm
 * 记忆是后台的事，这页只管理，不做确认、「不对」、「新」这类标记（用户 09-27 定的）。
 */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val colors = DaodianColors.current
    val vm = activityViewModel<MemoryViewModel>()
    val memories by vm.memories.collectAsState()

    /** 底纸开着：改哪一条；[Editing.memory] 为 null 是新记一条 */
    var editing by remember { mutableStateOf<Editing?>(null) }
    var undo by remember { mutableStateOf<Memory?>(null) }
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(5_000)
            undo = null
        }
    }

    fun delete(m: Memory) {
        vm.delete(m)
        undo = m
    }

    Box(Modifier.fillMaxSize().background(colors.paper)) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(title = "记忆", onBack = onBack) {
                IconTapTarget(onClick = { editing = Editing(null) }) { PlusIcon(tint = colors.ink2) }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
            ) {
                val list = memories
                when {
                    list == null -> Unit
                    list.isEmpty() -> Empty()
                    else -> {
                        Text(
                            "聊天时它会带上这些，好听懂你的话。点一条改，左滑删。",
                            style = DaodianType.settingNote, color = colors.muted,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        GroupLabel("${list.size} 条")
                        PaperGroup {
                            list.forEachIndexed { i, m ->
                                if (i > 0) GroupRule()
                                key(m.id) {
                                    SwipeToDelete(onDelete = { delete(m) }, rowColor = colors.surface) {
                                        MemoryRow(m, onOpen = { editing = Editing(m) })
                                    }
                                }
                            }
                        }
                    }
                }
                // 给撤销条留出地方，最后一条不被它盖住
                Spacer(Modifier.height(96.dp))
            }
        }

        UndoBar(
            label = undo?.let { "删了「${it.text}」" },
            onUndo = {
                undo?.let(vm::restore)
                undo = null
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    editing?.let { e ->
        MemorySheet(
            editing = e,
            onSave = { text -> vm.save(e.memory?.id, text) },
            onDelete = { e.memory?.let(::delete) },
            onDismiss = { editing = null }
        )
    }
}

private data class Editing(val memory: Memory?)

@Composable
private fun MemoryRow(m: Memory, onOpen: () -> Unit) {
    val colors = DaodianColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 18.dp, end = 16.dp, top = 13.dp, bottom = 13.dp)
    ) {
        Text(m.text, style = DaodianType.body, color = colors.ink)
        Spacer(Modifier.height(3.dp))
        Text(day(m.updatedAt), style = DaodianType.settingNote, color = colors.hint)
    }
}

/** 一条都没有：一句宋体大字 + 怎么会有 */
@Composable
private fun Empty() {
    val colors = DaodianColors.current
    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 36.dp)) {
        Text("还没记下什么", style = DaodianType.cardTitle, color = colors.ink)
        Spacer(Modifier.height(12.dp))
        Text(
            "聊天时说「记住……」，它就记下；聊完停一会儿，它也会自己从对话里找值得记的。也可以点右上角「+」自己写一条。",
            style = DaodianType.bodySmall, color = colors.ink2
        )
    }
}

/**
 * 改一条 / 记一条的底纸：一个输入框、字数、「删掉这条」和「存」。
 * 不合规（太长、像卡号、记满了）不关纸，原因写在字数底下 —— 和模型记的走同一道规矩
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemorySheet(
    editing: Editing,
    onSave: suspend (String) -> String?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = DaodianColors.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val m = editing.memory
    var text by remember { mutableStateOf(m?.text.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    // 新记一条直接弹键盘；改已有的先看清楚再说
    if (m == null) LaunchedEffect(Unit) { focus.requestFocus() }

    fun close(then: () -> Unit = {}) {
        scope.launch { sheet.hide() }.invokeOnCompletion {
            then()
            onDismiss()
        }
    }

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
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (m == null) "记一条" else "改这一条",
                    style = DaodianType.rowTitle.copy(fontSize = 17.sp), color = colors.ink,
                    modifier = Modifier.weight(1f)
                )
                if (m != null) {
                    Text(
                        if (m.createdAt == m.updatedAt) "${day(m.createdAt)} 记下" else "${day(m.updatedAt)} 改过",
                        style = DaodianType.sectionLabel, color = colors.hint
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.paper, RoundedCornerShape(5.dp))
                    .border(1.dp, if (problem != null) colors.red else colors.rule2, RoundedCornerShape(5.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                val style = DaodianType.body.copy(fontSize = 16.sp, lineHeight = 26.sp)
                if (text.isEmpty()) Text("「晚点」一般指晚上 9 点", style = style, color = colors.hint)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it; problem = null },
                    textStyle = style.copy(color = colors.ink),
                    minLines = 3,
                    cursorBrush = SolidColor(colors.ink),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
            Spacer(Modifier.height(8.dp))
            val length = text.trim().length
            Text(
                "$length / ${MemoryRules.MAX_LENGTH}",
                style = DaodianType.caption,
                color = if (length > MemoryRules.MAX_LENGTH) colors.red else colors.hint,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            problem?.let {
                Text(it, style = DaodianType.caption, color = colors.red, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp))
            }
            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m != null) {
                    Box(
                        Modifier.heightIn(min = 44.dp).clickable { close(then = onDelete) }.padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("删掉这条", style = DaodianType.button.copy(fontSize = 14.sp), color = colors.red)
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.solid)
                        .clickable {
                            scope.launch {
                                val p = onSave(text)
                                if (p == null) close() else problem = p
                            }
                        }
                        .padding(horizontal = 30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("存", style = DaodianType.button, color = colors.onSolid)
                }
            }
        }
    }
}

/** 「9月20日」；不是今年的带上年份 */
private fun day(millis: Long): String {
    val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    val md = "${d.monthValue}月${d.dayOfMonth}日"
    return if (d.year == LocalDate.now().year) md else "${d.year}年$md"
}

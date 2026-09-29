package com.abc.daodian.ledger.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.ledger.domain.LedgerTags
import com.abc.daodian.ledger.domain.LedgerTags.Found
import com.abc.daodian.ledger.domain.TagBoard
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import com.abc.daodian.shared.ui.InkChip
import com.abc.daodian.shared.ui.OneLineRow
import com.abc.daodian.shared.ui.PlusIcon
import com.abc.daodian.shared.ui.activityViewModel
import com.abc.daodian.shared.ui.dashedBorder
import kotlinx.coroutines.delay

/*
 * 打标签（设计稿：https://claude.ai/artifact/7Cx7HtXU1pVkXn3ghwhXQm —— 对账选了方向 B、账单页选了方向 A）。
 * 两处一套零件：候选只排一行，放不下的不画；想要的不在里面就打字找，找不到就新建。
 * 点了就落库（挂上 / 取下），不等「好了」「就这样」。规矩见 DESIGN.md §10.4「标签怎么打」
 */

private val TagShape = RoundedCornerShape(14.dp)

// ---------------- 对账问卡：一笔底下 ----------------

/**
 * 问卡上一笔底下：平时一行灰字「＋ 打标签」，不打的账不占地方；点开是一行候选 + 虚线「＋」，
 * 再点「＋」换成输入框打字找。收起后是挂着的小黑块 +「改」
 */
@Composable
fun AskTagRow(txnId: Long) {
    val vm = activityViewModel<LedgerViewModel>()
    val flow = remember(txnId) { vm.tags(txnId) }
    val board by flow.collectAsState(initial = null)
    val b = board ?: return
    val categories by vm.categoryNodes.collectAsState()
    var open by rememberSaveable(txnId) { mutableStateOf(false) }
    var searching by rememberSaveable(txnId) { mutableStateOf(false) }
    var query by rememberSaveable(txnId) { mutableStateOf("") }

    fun set(name: String, on: Boolean) = vm.setTag(txnId, name, on, "对账问卡")
    fun done() {
        searching = false
        query = ""
    }

    Column(Modifier.animateContentSize(Motion.flow())) {
        if (!open) {
            Row(
                Modifier
                    .padding(top = 4.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { open = true }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val colors = DaodianColors.current
                if (b.attached.isEmpty()) {
                    PlusIcon(size = 11.dp, tint = colors.muted, strokeWidth = 1.3.dp)
                    Text("打标签", style = DaodianType.caption, color = colors.muted)
                } else {
                    b.attached.forEach { MiniTag(it) }
                    Text("改", style = DaodianType.caption, color = colors.muted, modifier = Modifier.padding(start = 2.dp))
                }
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(Motion.settle()) + fadeIn(Motion.flow(delay = 60)),
            exit = shrinkVertically(Motion.flow()) + fadeOut(Motion.flow(Motion.SHORT))
        ) {
            Crossfade(searching, animationSpec = tween(Motion.MID), label = "askTagSearch") { s ->
                if (!s) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            OneLineRow(Modifier.weight(1f, fill = false)) {
                                b.ranked.forEach { name -> key(name) { TagChip(name, name in b.attached) { set(name, name !in b.attached) } } }
                            }
                            Spacer(Modifier.width(6.dp))
                            PlusChip { searching = true }
                        }
                        TextAction("收起", Modifier.padding(start = 10.dp)) { open = false }
                    }
                } else {
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        val found = LedgerTags.find(query, b.all, categories)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TagField(
                                query, { query = it }, placeholderOf(b), height = 32.dp,
                                modifier = Modifier.weight(1f), autoFocus = true,
                                onDone = { doneWith(found, b)?.let { (n, on) -> set(n, on); done() } }
                            )
                            TextAction("取消", Modifier.padding(start = 12.dp)) { done() }
                        }
                        TagResults(
                            board = b, found = found, withAttached = true, categoryTail = "",
                            onToggle = { set(it, it !in b.attached); done() },
                            onUse = { if (it !in b.attached) set(it, true); done() },
                            onCreate = { set(it, true); done() }
                        )
                    }
                }
            }
        }
    }
}

/** 收起后挂着的一个：22 高的小黑块 */
@Composable
private fun MiniTag(name: String) {
    val colors = DaodianColors.current
    Box(
        Modifier.height(22.dp).background(colors.solid, RoundedCornerShape(11.dp)).padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(name, style = DaodianType.caption.copy(fontSize = 12.sp), color = colors.onSolid, maxLines = 1)
    }
}

// ---------------- 账单页：信息卡里「标签」那一行 ----------------

/**
 * 账单页信息卡里的「标签」：挂着的 + 虚线「＋」。点了原地展开编辑：输入框 + 一行候选，
 * 打字就换成找到的；点挂着的取下（先收窄再落库）。[editable] = false（作废的那笔）只看
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TxnTagRow(vm: LedgerViewModel, txnId: Long, editable: Boolean) {
    val colors = DaodianColors.current
    val flow = remember(txnId) { vm.tags(txnId) }
    val board by flow.collectAsState(initial = null)
    val b = board ?: return
    val categories by vm.categoryNodes.collectAsState()
    var editing by rememberSaveable(txnId) { mutableStateOf(false) }
    var query by rememberSaveable(txnId) { mutableStateOf("") }
    // 取下时先收窄（160ms 退场），再落库
    var leaving by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(leaving) {
        val name = leaving ?: return@LaunchedEffect
        delay(Motion.SHORT.toLong())
        vm.setTag(txnId, name, false, "账单页")
    }
    // 真取下了再放开：之后再挂上同一个，照样长出来
    LaunchedEffect(b.attached) { if (leaving != null && leaving !in b.attached) leaving = null }
    // 第一次画出来时就挂着的不长出来；之后新挂上的从 0 宽长出来
    val initial = remember { b.attached.toSet() }

    fun set(name: String, on: Boolean) = vm.setTag(txnId, name, on, "账单页")

    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.width(52.dp).height(28.dp), contentAlignment = Alignment.CenterStart) {
                Text("标签", style = DaodianType.bodySmall, color = colors.muted)
            }
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (b.attached.isEmpty() && !editing) {
                    Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                        Text("没有", style = DaodianType.bodySmall, color = colors.hint)
                    }
                }
                b.attached.forEach { name ->
                    key(name) {
                        val state = remember { MutableTransitionState(name in initial).apply { targetState = true } }
                        SideEffect { state.targetState = name != leaving }
                        AnimatedVisibility(
                            visibleState = state,
                            enter = expandHorizontally(Motion.settle()) + fadeIn(Motion.flow()),
                            exit = shrinkHorizontally(Motion.exit()) + fadeOut(Motion.exit())
                        ) {
                            TagChip(name, on = true) {
                                when {
                                    !editable -> Unit
                                    !editing -> editing = true
                                    else -> leaving = name
                                }
                            }
                        }
                    }
                }
                if (editable && !editing) PlusChip { editing = true }
                if (editing) {
                    Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                        TextAction("好了", color = colors.ink2) {
                            editing = false
                            query = ""
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = editing,
            enter = expandVertically(Motion.settle()) + fadeIn(Motion.flow(delay = 60)),
            exit = shrinkVertically(Motion.flow()) + fadeOut(Motion.flow(Motion.SHORT))
        ) {
            Column(
                Modifier.animateContentSize(Motion.flow()).padding(top = 2.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val found = LedgerTags.find(query, b.all, categories)
                TagField(
                    query, { query = it }, placeholderOf(b), height = 36.dp, modifier = Modifier.fillMaxWidth(),
                    onDone = { doneWith(found, b)?.let { (n, on) -> set(n, on); query = "" } }
                )
                TagResults(
                    board = b, found = found, withAttached = false, categoryTail = "这笔的类别去对话里改。",
                    onToggle = { set(it, it !in b.attached); query = "" },
                    onUse = { if (it !in b.attached) set(it, true); query = "" },
                    onCreate = { set(it, true); query = "" }
                )
            }
        }
    }
}

// ---------------- 共用零件 ----------------

private fun placeholderOf(b: TagBoard) = if (b.all.isEmpty()) "起个名字：约会、报销、国庆回老家" else "从 ${b.all.size} 个里找，或起个新名字"

/** 输入框里按「完成」：一模一样的那个挂上（挂着就取下），没有就新建。像已有的、撞了类别的不动 */
private fun doneWith(found: Found, b: TagBoard): Pair<String, Boolean>? = when (found) {
    is Found.Matches -> when {
        found.create != null -> found.create to true
        else -> found.names.first().let { it to (it !in b.attached) }
    }
    else -> null
}

/**
 * 输入框底下：没打字是一行候选（[withAttached]：挂着的也排在里面，实心），
 * 打了字是找到的几个 + 「＋ 新建」；像已有的、撞了类别的，说一句
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagResults(
    board: TagBoard,
    found: Found,
    withAttached: Boolean,
    categoryTail: String,
    onToggle: (String) -> Unit,
    onUse: (String) -> Unit,
    onCreate: (String) -> Unit
) {
    val colors = DaodianColors.current
    // 高度随着变的那一下交给外面的 animateContentSize；字一换就换，不等淡入淡出
    when (found) {
        Found.Nothing -> {
            val names = if (withAttached) board.ranked else board.ranked.filter { it !in board.attached }
            OneLineRow {
                names.forEach { name -> key(name) { TagChip(name, name in board.attached) { onToggle(name) } } }
            }
        }
        is Found.Matches -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            found.names.forEach { name -> key(name) { TagChip(name, name in board.attached) { onToggle(name) } } }
            found.create?.let { NewTagChip(it) { onCreate(it) } }
        }
        is Found.Near -> FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("已经有「${found.existing}」了", style = DaodianType.caption, color = colors.ink2, modifier = Modifier.padding(vertical = 6.dp))
            TextAction("用「${found.existing}」", color = colors.accent) { onUse(found.existing) }
            TextAction("还是新建「${found.typed}」") { onCreate(found.typed) }
        }
        is Found.Category -> Text(
            "「${found.typed}」是类别（${found.path}），不是标签。$categoryTail",
            style = DaodianType.caption.copy(lineHeight = 18.sp), color = colors.muted
        )
    }
}

/** 一个标签：墨洇小块，比问卡的猜测矮一号（28 对 34），一眼分得开 */
@Composable
private fun TagChip(name: String, on: Boolean, onClick: () -> Unit) {
    InkChip(
        selected = on, dimmed = false, enabled = true, shape = TagShape,
        modifier = Modifier.height(28.dp), onClick = onClick
    ) { ink ->
        Text(
            name, style = DaodianType.bodySmall.copy(fontSize = 13.sp), color = ink.fg, maxLines = 1,
            modifier = Modifier.padding(horizontal = 11.dp)
        )
    }
}

/** 「＋ 新建『出差』」：朱砂虚线，建好直接挂上 */
@Composable
private fun NewTagChip(name: String, onClick: () -> Unit) {
    val colors = DaodianColors.current
    InkChip(
        selected = false, dimmed = false, enabled = true, dashed = true, accent = true, shape = TagShape,
        modifier = Modifier.height(28.dp), onClick = onClick
    ) {
        Text(
            "＋ 新建「$name」", style = DaodianType.bodySmall.copy(fontSize = 13.sp), color = colors.accent, maxLines = 1,
            modifier = Modifier.padding(horizontal = 11.dp)
        )
    }
}

/** 虚线圆「＋」：点了打字找 */
@Composable
private fun PlusChip(onClick: () -> Unit) {
    val colors = DaodianColors.current
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .dashedBorder(colors.rule2, 14.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "找标签，或新建一个" },
        contentAlignment = Alignment.Center
    ) {
        PlusIcon(size = 11.dp, tint = colors.muted, strokeWidth = 1.3.dp)
    }
}

@Composable
private fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = DaodianColors.current.muted, onClick: () -> Unit) {
    Text(
        text, style = DaodianType.caption, color = color, maxLines = 1,
        modifier = modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp)
    )
}

/** 找标签的输入框：一粒药丸，聚焦时边变成墨色 */
@Composable
private fun TagField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    height: Dp,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    onDone: () -> Unit
) {
    val colors = DaodianColors.current
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val edge by animateColorAsState(if (focused) colors.ink else colors.rule2, Motion.flow(Motion.SHORT), label = "tagField")
    val shape = RoundedCornerShape(height / 2)
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = DaodianType.bodySmall.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.height(height).focusRequester(focus).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().background(colors.paper, shape).border(1.dp, edge, shape).padding(horizontal = 13.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) Text(placeholder, style = DaodianType.bodySmall, color = colors.hint, maxLines = 1)
                inner()
            }
        }
    )
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
}

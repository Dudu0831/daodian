package com.abc.daodian.ledger.presentation

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abc.daodian.ledger.capture.AppCatalog
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.CheckIcon
import com.abc.daodian.shared.ui.FixLink
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.Marker
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SearchIcon
import com.abc.daodian.shared.ui.SettingRow

/**
 * 听哪些 app：上面一段是在听的，下面一段是其他装着的，顶上能搜。没有内置的，全由你勾（DESIGN.md §10.2）。
 * 设计稿方向 A：https://claude.ai/artifact/RyPMu2X4dK4Q9kCM1NfXLu
 */
@Composable
fun ListenAppsScreen(vm: ListenAppsViewModel, onBack: () -> Unit) {
    val colors = DaodianColors.current
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val listened by vm.listened.collectAsState()
    val icons by vm.icons.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    LifecycleResumeEffect(Unit) {
        vm.reloadIfUnreadable()
        onPauseOrDispose { }
    }

    @Composable
    fun Rows(apps: List<AppCatalog.App>) {
        apps.forEachIndexed { i, a ->
            if (i > 0) GroupRule()
            val checked = a.pkg in listened
            AppLine(
                app = a,
                icon = icons[a.pkg],
                checked = checked,
                note = when {
                    a.pkg in state?.gone.orEmpty() -> "这台手机上找不到了"
                    checked -> vm.alsoCaptures(a.pkg)
                    else -> null
                },
                warn = a.pkg !in state?.gone.orEmpty(),
                onToggle = { vm.toggle(a.pkg, it) }
            )
        }
    }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar("听哪些 app", onBack)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues()
        ) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "勾上的 app 发的通知，原样存下来交给模型整理；没勾的，一条都不存。",
                        style = DaodianType.settingNote,
                        color = colors.muted,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    SearchField(query, onChange = { query = it })
                }
            }

            val s = state
            if (s == null) {
                item(key = "loading") {
                    Text(
                        "在看手机上装了哪些 app……",
                        style = DaodianType.caption,
                        color = colors.hint,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp)
                    )
                }
                return@LazyColumn
            }

            if (!s.readable) {
                item(key = "unreadable") {
                    Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
                        PaperGroup {
                            SettingRow(
                                title = "读取应用列表",
                                note = "系统没让「到点」看装了哪些 app，这里只列得出已经勾上的 —— 点这里去系统设置里允许",
                                noteColor = colors.red,
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                                leading = { Marker(false) }
                            ) { FixLink() }
                        }
                    }
                }
            }

            val needle = query.trim()
            if (needle.isEmpty()) {
                item(key = "top") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        GroupLabel("在听的 · ${listened.size}")
                        PaperGroup {
                            if (s.top.isEmpty()) {
                                Text(
                                    "还一个都没勾。银行、支付宝这类付了钱会发通知的 app 勾上，记账才收得到。",
                                    style = DaodianType.settingNote,
                                    color = colors.muted,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp)
                                )
                            } else {
                                Rows(s.top)
                            }
                        }
                    }
                }
                if (s.rest.isNotEmpty()) {
                    item(key = "rest") {
                        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                            GroupLabel("其他 app · ${s.rest.size}")
                            PaperGroup { Rows(s.rest) }
                            Text(
                                "只列桌面上有图标的 app。刚勾上的留在原处，下次进来排到上面。",
                                style = DaodianType.settingNote,
                                color = colors.hint,
                                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp)
                            )
                        }
                    }
                }
            } else {
                val hits = s.all.filter { it.label.contains(needle, ignoreCase = true) || it.pkg.contains(needle, ignoreCase = true) }
                item(key = "hits") {
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                        GroupLabel("搜到 ${hits.size} 个")
                        if (hits.isEmpty()) {
                            Text(
                                "这台手机上没有叫这个的 app。",
                                style = DaodianType.bodySmall,
                                color = colors.muted,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        } else {
                            PaperGroup { Rows(hits) }
                        }
                    }
                }
            }
        }
    }
}

/** 一个 app：图标、名字、（勾上会顺带存下什么 / 找不到了），行尾方框。整行点着勾 */
@Composable
private fun AppLine(app: AppCatalog.App, icon: ImageBitmap?, checked: Boolean, note: String?, warn: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = DaodianColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app.label, icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = DaodianType.rowTitle, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                Text(note, style = DaodianType.settingNote, color = if (warn) colors.red else colors.muted)
            }
        }
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier.size(20.dp).border(1.3.dp, if (checked) colors.accent else colors.rule2, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) CheckIcon(size = 13.dp, tint = colors.accent, strokeWidth = 1.6.dp)
        }
    }
}

/** 系统给的图标；还没画出来 / 拿不到时是名字的头一个字 */
@Composable
private fun AppIcon(label: String, icon: ImageBitmap?) {
    val colors = DaodianColors.current
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(32.dp))
    } else {
        Box(
            Modifier.size(32.dp).background(colors.surfaceAlt, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(label.take(1), style = DaodianType.rowTitle.copy(fontSize = 14.sp), color = colors.ink2)
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val colors = DaodianColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(5.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = DaodianType.body.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().height(44.dp),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxSize()
                    .background(colors.surface, shape)
                    .border(1.dp, colors.rule, shape)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchIcon(tint = colors.hint)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("搜 app 名字", style = DaodianType.body, color = colors.hint, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Text(
                        "清空",
                        style = DaodianType.caption,
                        color = colors.muted,
                        modifier = Modifier.clickable { onChange("") }.padding(horizontal = 10.dp, vertical = 12.dp)
                    )
                }
            }
        }
    )
}

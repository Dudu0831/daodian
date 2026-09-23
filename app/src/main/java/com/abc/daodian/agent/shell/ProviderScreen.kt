package com.abc.daodian.agent.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.abc.daodian.agent.model.provider.PingResult
import com.abc.daodian.agent.model.provider.ProviderStore
import com.abc.daodian.agent.conversation.ChatViewModel
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import kotlinx.coroutines.launch

/**
 * 模型服务配置页 —— 顶栏那枚印 → 纸签末行「改配置」进来的地方。见 DESIGN.md 决策 8.4
 *
 * 三格 + 一个开关：网关地址、key、模型，外加「先想一想再答」（[com.abc.daodian.agent.model.provider.ProviderProfile.thinking]）。
 *
 * 「测一下」用框里**正在填**的值，不用先保存。测没过也能存 ——
 * 有些网关对这句测试话会挑刺，正式调用反而是通的，拦死了就没法绕过去了。
 */
@Composable
fun ProviderScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val colors = DaodianColors.current
    val profile by vm.profile.collectAsState()
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var keyShown by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var ping by remember { mutableStateOf<PingResult?>(null) }
    var askDiscard by remember { mutableStateOf(false) }

    // 只灌一次：存完 profile 会再发一遍，那时候不能把用户正在改的字冲掉
    LaunchedEffect(profile) {
        if (!loaded) {
            baseUrl = profile.baseUrl
            apiKey = profile.apiKey
            model = profile.model
            thinking = profile.thinking
            loaded = true
        }
    }

    val dirty = loaded &&
        (baseUrl != profile.baseUrl || apiKey != profile.apiKey || model != profile.model ||
            thinking != profile.thinking)

    fun save() {
        vm.saveProvider(baseUrl, apiKey, model, thinking)
        onBack()
    }

    // 手打一遍 key 很烦，别一按返回就没了
    fun leave() {
        if (dirty) askDiscard = true else onBack()
    }
    BackHandler(enabled = dirty) { askDiscard = true }

    Column(Modifier.fillMaxSize().background(colors.paper).imePadding()) {
        ScreenTopBar(title = "模型服务", onBack = { leave() }) {
            TextButton(onClick = { save() }, enabled = dirty) {
                Text("保存", color = if (dirty) colors.accent else colors.hint)
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {

            FieldLabel("网关地址")
            Field(baseUrl, { baseUrl = it; ping = null }, "https://api.example.com/v1")
            Help("填到 /v1 为止，后面的路径 app 自己拼")
            Spacer(Modifier.height(22.dp))

            FieldLabel("KEY")
            Field(
                value = apiKey,
                onChange = { apiKey = it; ping = null },
                placeholder = "sk-…",
                // 关掉联想和自动大写，免得 key 进输入法词库
                keyboard = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Next
                ),
                visual = if (keyShown) VisualTransformation.None else PasswordVisualTransformation('•'),
                trailing = {
                    Text(
                        if (keyShown) "隐藏" else "显示",
                        style = DaodianType.caption,
                        color = colors.muted,
                        modifier = Modifier.clickable { keyShown = !keyShown }.padding(start = 10.dp)
                    )
                }
            )
            Help("只存在这台手机上，不进备份")
            Spacer(Modifier.height(22.dp))

            FieldLabel("模型")
            Field(model, { model = it; ping = null }, "gpt-4.1-mini")
            Help("照网关里的名字填")
            Spacer(Modifier.height(22.dp))

            Row(
                Modifier.fillMaxWidth().clickable { thinking = !thinking; ping = null },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("先想一想再答", style = DaodianType.body, color = colors.ink)
                    Help(
                        if (thinking) "会多等几秒，想的过程在对话里能展开看；模型不支持就会报错"
                        else "直接答，最快。说得绕的句子可能推错时间"
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = thinking,
                    onCheckedChange = { thinking = it; ping = null },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = colors.solid,
                        checkedThumbColor = colors.onSolid,
                        checkedBorderColor = colors.solid,
                        uncheckedTrackColor = colors.surfaceAlt,
                        uncheckedThumbColor = colors.rule2,
                        uncheckedBorderColor = colors.rule2
                    )
                )
            }
            Spacer(Modifier.height(26.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .border(1.dp, colors.rule2, RoundedCornerShape(22.dp))
                        .clickable(enabled = !testing) {
                            testing = true
                            ping = null
                            scope.launch {
                                ping = vm.testProvider(baseUrl, apiKey, model, thinking)
                                testing = false
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Text(if (testing) "在测…" else "测一下", style = DaodianType.bodySmall, color = colors.ink)
                }
                Spacer(Modifier.height(0.dp))
                when (val p = ping) {
                    is PingResult.Ok -> Text(
                        "连得上 · ${"%.1f".format(p.millis / 1000.0)} 秒",
                        style = DaodianType.bodySmall,
                        color = colors.ink2,
                        modifier = Modifier.padding(start = 14.dp)
                    )
                    is PingResult.Failed -> Text(
                        p.why,
                        style = DaodianType.bodySmall,
                        color = colors.red,
                        modifier = Modifier.padding(start = 14.dp)
                    )
                    null -> Unit
                }
            }

            (ping as? PingResult.Failed)?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it.raw,
                    style = DaodianType.basis,
                    color = colors.muted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceAlt, RoundedCornerShape(5.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(30.dp))
            Text(
                "恢复成打包时的配置",
                style = DaodianType.caption,
                color = colors.muted,
                modifier = Modifier.clickable {
                    val seed = ProviderStore.seed
                    baseUrl = seed.baseUrl
                    apiKey = seed.apiKey
                    model = seed.model
                    thinking = seed.thinking
                    ping = null
                }
            )
            Spacer(Modifier.height(6.dp))
            Text("填回 secrets.properties 里那份，还要点保存才生效", style = DaodianType.caption, color = colors.hint)
            Spacer(Modifier.height(40.dp))
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("改动还没保存") },
            confirmButton = { TextButton(onClick = { askDiscard = false; save() }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { askDiscard = false; onBack() }) { Text("丢掉") } }
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    val colors = DaodianColors.current
    Text(text, style = DaodianType.sectionLabel, color = colors.muted, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Help(text: String) {
    val colors = DaodianColors.current
    Text(text, style = DaodianType.caption, color = colors.muted, modifier = Modifier.padding(top = 6.dp))
}

/** 和编辑页 PlainField 同一套外观：surface 底、1dp rule 描边、圆角 5dp */
@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null
) {
    val colors = DaodianColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(5.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(5.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = DaodianType.body, color = colors.hint)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = DaodianType.body.copy(color = colors.ink),
                singleLine = true,
                keyboardOptions = keyboard,
                visualTransformation = visual,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.ink),
                modifier = Modifier.fillMaxWidth()
            )
        }
        trailing?.invoke()
    }
}

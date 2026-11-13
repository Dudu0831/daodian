package com.abc.daodian.assistant.conversation.turn

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.assistant.conversation.data.ConversationAudioFiles
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/** 条宽：96 起，每秒 +3，264 封顶（原型的数，和文字气泡最宽一样） */
private fun barWidth(seconds: Float) = minOf(264f, 96f + seconds * 3f)

/** 只写秒：`7″`。按住最长 60 秒，不会出现分钟 */
internal fun voiceDuration(ms: Long) = "${maxOf(1, (ms / 1000f).roundToInt())}″"

private val BarShape = RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp)
private val Duration = DaodianType.cardTitle.copy(fontSize = 14.sp, letterSpacing = 0.02.sp)
private val Transcript = DaodianType.body.copy(fontSize = 13.sp, lineHeight = 21.sp)

/** 字一个一个洇出来：每个字晚 28ms，各用 220ms 从无到有 */
private const val INK_STAGGER_MS = 28
private const val INK_FADE_MS = 220

/**
 * 对话里的语音条（§8.3，原型 https://claude.ai/artifact/VtJp6zsX1jJJSarSxQwY3U「乙」）。
 *
 * 墨色实心靠右：▶ + 波形 + `7″`，宽度随时长。点一下播放 / 暂停，一次只放一条（[VoicePlayback]）。
 * 默认不显示字；长按不弹菜单，条轻轻一缩，字直接在条下面一个一个洇出来。
 * 不记状态：滚出屏幕、下次进来又收着
 */
@Composable
fun VoiceBubble(path: String, text: String) {
    val colors = DaodianColors.current
    val context = LocalContext.current
    val ms = remember(path) { ConversationAudioFiles.durationMs(path) }
    val peaks = remember(path) { ConversationAudioFiles.peaks(path) }
    val seconds = ms / 1000f
    val width = barWidth(seconds)
    val bars = maxOf(6, floor((width - 70) / 5).toInt())
    val playing = VoicePlayback.current == path && VoicePlayback.playing
    val progress = if (VoicePlayback.current == path) VoicePlayback.progress else 0f
    var revealed by remember(path) { mutableStateOf(false) }
    val squeeze = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val label = "语音 ${voiceDuration(ms)}"

    fun reveal() {
        if (revealed) return
        revealed = true
        scope.launch {
            squeeze.animateTo(0.97f, tween(70))
            squeeze.animateTo(1f, tween(70))
        }
    }

    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .width(width.dp)
                .height(44.dp)
                .graphicsLayer {
                    scaleX = squeeze.value
                    scaleY = squeeze.value
                }
                .background(colors.solid, BarShape)
                .pointerInput(path) {
                    detectTapGestures(
                        onTap = { VoicePlayback.toggle(context, path) },
                        onLongPress = { reveal() }
                    )
                }
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    onClick(if (playing) "暂停" else "播放") { VoicePlayback.toggle(context, path); true }
                    onLongClick("转文字") { reveal(); true }
                }
                .padding(start = 14.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PlayGlyph(playing, colors.onSolid)
            Canvas(Modifier.weight(1f).height(22.dp)) {
                val w = 2.5.dp.toPx()
                val step = w + 2.5.dp.toPx()
                val n = minOf(bars, ((size.width + 2.5.dp.toPx()) / step).toInt())
                for (i in 0 until n) {
                    val p = if (peaks.isEmpty()) 0.4f else peaks[(i * peaks.size / n).coerceIn(0, peaks.lastIndex)]
                    val h = (4 + p * 16).dp.toPx()
                    drawRoundRect(
                        color = colors.onSolid.copy(alpha = if (i.toFloat() / n < progress) 1f else 0.38f),
                        topLeft = Offset(i * step, (size.height - h) / 2),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(w / 2)
                    )
                }
            }
            Text(voiceDuration(ms), style = Duration, color = colors.onSolid, maxLines = 1)
        }
        if (revealed && text.isNotBlank()) InkInText(text)
    }
}

/** 长按出来的那段字：浅灰小字，每个字依次从无到有 */
@Composable
private fun InkInText(text: String) {
    val colors = DaodianColors.current
    val total = text.length * INK_STAGGER_MS + INK_FADE_MS
    val clock = remember(text) { Animatable(0f) }
    LaunchedEffect(text) { clock.animateTo(total.toFloat(), tween(total, easing = LinearEasing)) }
    val t = clock.value
    val shown = buildAnnotatedString {
        text.forEachIndexed { i, c ->
            val a = ((t - i * INK_STAGGER_MS) / INK_FADE_MS).coerceIn(0f, 1f)
            withStyle(SpanStyle(color = colors.muted.copy(alpha = colors.muted.alpha * a))) { append(c) }
        }
    }
    Text(shown, style = Transcript, modifier = Modifier.widthIn(max = 264.dp).padding(horizontal = 4.dp))
}

@Composable
private fun PlayGlyph(playing: Boolean, tint: Color) {
    Canvas(Modifier.size(14.dp)) {
        val k = size.width / 14f
        if (playing) {
            val sw = 2.2f * k
            drawLine(tint, Offset(3.5f * k, 2 * k), Offset(3.5f * k, 12 * k), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(tint, Offset(10.5f * k, 2 * k), Offset(10.5f * k, 12 * k), strokeWidth = sw, cap = StrokeCap.Round)
        } else {
            drawPath(
                Path().apply {
                    moveTo(3 * k, 1.8f * k)
                    lineTo(3 * k, 12.2f * k)
                    lineTo(12 * k, 7 * k)
                    close()
                },
                tint
            )
        }
    }
}

/**
 * 语音条的回放：全 app 一次只放一条。点另一条，这条停下、回到开头；点正在放的那条是暂停，再点接着放。
 * 对话页退到后台、按住说话时调 [stop]。状态都在主线程上改
 */
object VoicePlayback {
    /** 正在放 / 暂停着的那条 */
    var current by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    /** 放到哪儿了，0..1 */
    var progress by mutableFloatStateOf(0f)
        private set

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun toggle(context: Context, path: String) {
        val p = player
        when {
            current == path && p != null && playing -> {
                p.pause()
                playing = false
                ticker?.cancel()
            }
            current == path && p != null -> {
                p.start()
                playing = true
                tick()
            }
            else -> start(context, path)
        }
    }

    fun stop() {
        ticker?.cancel()
        player?.release()
        player = null
        current = null
        playing = false
        progress = 0f
    }

    private fun start(context: Context, path: String) {
        stop()
        val p = MediaPlayer()
        runCatching {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(path)
            p.prepare()
        }.onFailure {
            // 文件没了（清过数据之类）：这一下当没点
            p.release()
            return
        }
        p.setOnCompletionListener { if (player === p) stop() }
        player = p
        current = path
        playing = true
        progress = 0f
        p.start()
        tick()
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                val total = p.duration.takeIf { it > 0 } ?: break
                progress = (p.currentPosition.toFloat() / total).coerceIn(0f, 1f)
                delay(50)
            }
        }
    }
}

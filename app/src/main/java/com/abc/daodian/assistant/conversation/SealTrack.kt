package com.abc.daodian.assistant.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.abc.daodian.runtime.model.ApiState
import com.abc.daodian.runtime.model.Effort
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 想多久：点一下顶栏的印，印往下长成一根浮起来的胶囊，印就是胶囊里的滑块（DESIGN.md §8.4，用户 11-20 定的）。
 *
 * - 关着时印只能点，不能按住拉。点开时胶囊从印外面长出来，印顺着滑槽滑到现在那一档；最上头就是印原来的位置（快答）。
 * - 开着时点哪一档，印滑到哪；按住印（或槽上任何地方）上下拖，只在第一档和最后一档之间走，浓淡连续变，跨过一档轻跳一下（聊天里不震动，§6.5）。
 *   松手吸到最近那档。选完停一下，胶囊缩回成印，换了档就在原处盖一下章。
 * - 点胶囊外面、按返回，或者再点一下印：不改，缩回去。
 * - 胶囊中间一道比印窄的凹槽，印骑在上面；走过的那段染墨。档位是刻在槽底的五道短横线。界面上不写档名，档名只给读屏念。
 *
 * 顶栏那枚印只管点开（[ProviderSeal]）；开着以后印、胶囊和手势都在覆盖层上（[SealTrackOverlay]）。
 */
@Stable
class SealTrack internal constructor(private val scope: CoroutineScope, private val onPick: (Effort) -> Unit) {

    enum class Phase {
        /** 印在顶栏上 */
        Idle,
        /** 胶囊开着（含长出来那一下），能点能拖 */
        Open,
        /** 缩回去的路上 */
        Back
    }

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** 印离最上头那一档往下多少 dp */
    var pos by mutableFloatStateOf(0f)
        private set

    /** 正拖着 */
    var dragging by mutableStateOf(false)
        private set

    /** 胶囊长出来了多少（0 只有一枚印、1 全长） */
    val grow = Animatable(0f)

    /** 跨过一档那一跳 */
    val bump = Animatable(0f)

    /** 刚进深想那一下晕圈胀开 */
    val halo = Animatable(0f)

    /** 换了档、缩回成印时盖一下章：加一次，顶栏的印照着盖 */
    var stamps by mutableIntStateOf(0)
        private set

    /** 刚选的，存进去之前先照它画，免得印闪回旧的浓淡 */
    var pending by mutableStateOf<Effort?>(null)
        private set

    private var chosenState by mutableStateOf(Effort.DEFAULT)

    /**
     * 现在存着的那档（每次重组由印写进来）。得是状态：从设置页回来对话页重建，新的 track 从默认档起，
     * 印写进存着的那档之后要再画一遍，不然印一直停在默认的浓淡（11-21 用户撞到的）
     */
    internal var chosen: Effort
        get() = chosenState
        set(value) {
            chosenState = value
            if (pending == value) pending = null
        }

    /** 印的中心，相对覆盖层，像素 */
    var home by mutableStateOf(Offset.Unspecified)
        private set
    internal var host: LayoutCoordinates? = null
    internal var seal: LayoutCoordinates? = null

    /** 这时候显示哪一档的浓淡（不算拖着的时候） */
    val shown: Effort get() = pending ?: chosen

    /** 印离哪一档最近 */
    val nearest: Int get() = nearest(pos)

    private var openedWith: Effort = Effort.DEFAULT
    private var job: Job? = null

    internal fun relayout() {
        val h = host?.takeIf { it.isAttached } ?: return
        val s = seal?.takeIf { it.isAttached } ?: return
        home = h.localPositionOf(s, Offset(s.size.width / 2f, s.size.height / 2f))
    }

    /** 点开：胶囊往下长，印滑到现在那一档 */
    fun open() {
        if (phase == Phase.Open) return
        job?.cancel()
        openedWith = shown
        phase = Phase.Open
        job = scope.launch {
            launch { grow.animateTo(1f, Motion.settle(GROW_MS)) }
            move(tick(shown.ordinal), Motion.settle(GROW_MS))
        }
    }

    /** 手又放上来了：选完等着缩回去的那一下不缩了 */
    fun hold() {
        job?.cancel()
        if (grow.value < 1f) scope.launch { grow.animateTo(1f, Motion.settle(GROW_MS)) }
    }

    fun grab() {
        job?.cancel()
        dragging = true
    }

    /** 拖到离最上头那一档 [p] dp 的地方。只在第一档和最后一档之间走，拉不过头 */
    fun dragTo(p: Float) {
        val before = nearest(pos)
        pos = p.coerceIn(0f, LAST)
        val now = nearest(pos)
        if (now != before) {
            scope.launch {
                bump.snapTo(1f)
                bump.animateTo(0f, tween(BUMP_MS))
            }
            if (now == 4) scope.launch {
                halo.snapTo(1f)
                halo.animateTo(0f, tween(240))
            }
        }
    }

    fun drop() {
        dragging = false
        pick(nearest(pos))
    }

    /** 选第 [i] 档：当场存下，印滑过去，停一下再缩回去 */
    fun pick(i: Int) {
        val effort = Effort.entries[i.coerceIn(0, 4)]
        if (effort != shown) {
            pending = effort
            onPick(effort)
        }
        job?.cancel()
        job = scope.launch {
            move(tick(i), tween(SLIDE_MS, easing = Motion.Settle))
            delay(LINGER_MS)
            collapse()
        }
    }

    /** 读屏上下滑换档：不演动画，直接定 */
    fun pickNow(i: Int) {
        val effort = Effort.entries[i.coerceIn(0, 4)]
        if (effort == shown) return
        pending = effort
        onPick(effort)
        stamps++
    }

    /** 不改，缩回去 */
    fun close() {
        if (phase != Phase.Open) return
        dragging = false
        job?.cancel()
        job = scope.launch { collapse() }
    }

    private suspend fun collapse() {
        phase = Phase.Back
        scope.launch { grow.animateTo(0f, Motion.flow(SHRINK_MS)) }
        move(0f, Motion.flow(SHRINK_MS))
        phase = Phase.Idle
        if (shown != openedWith) stamps++
    }

    private suspend fun move(to: Float, spec: AnimationSpec<Float>) {
        animate(pos, to, animationSpec = spec) { v, _ -> pos = v }
    }

    companion object {
        /** 相邻两档隔多远（dp）；最上头那档就是印原来的位置 */
        const val GAP = 36f
        const val LAST = GAP * 4

        const val GROW_MS = 260
        const val SHRINK_MS = 240
        const val SLIDE_MS = 160
        const val LINGER_MS = 400L
        const val BUMP_MS = 90

        fun tick(i: Int): Float = GAP * i

        fun nearest(pos: Float): Int = (pos / GAP).roundToInt().coerceIn(0, 4)
    }
}

/** 五档的印泥浓淡，从空印到满印（§8.4）。档名只给读屏念，界面上不写 */
internal object Ink {
    private val levels = floatArrayOf(0f, 0.14f, 0.32f, 0.70f, 1f)
    val names = listOf("快答", "稍想", "想一下", "多想", "深想")

    fun of(effort: Effort): Float = levels[effort.ordinal]

    /** 连续的档位（0–4）对应的浓淡：拖着走时一路变，不一格一格跳 */
    fun at(level: Float): Float {
        val c = level.coerceIn(0f, 4f)
        val lo = floor(c).toInt()
        val hi = minOf(4, lo + 1)
        return levels[lo] + (levels[hi] - levels[lo]) * (c - lo)
    }

    /** 多想起印边晕开：多想 2dp、深想 4dp */
    fun bleed(level: Float): Float = when {
        level < 3f -> 0f
        else -> 2f + 2f * (level - 3f).coerceIn(0f, 1f)
    }
}

@Composable
fun rememberSealTrack(onPick: (Effort) -> Unit): SealTrack {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPick)
    return remember { SealTrack(scope) { latest(it) } }
}

/**
 * 胶囊、胶囊里的印、连不上时的告警带。盖在顶栏和对话上面（对话页里和按住说话那一层同一个盒子）。
 * 开着时接点击和拖；缩回去的路上不接，触摸照样落到底下。
 */
@Composable
internal fun SealTrackOverlay(
    track: SealTrack,
    look: SealLook,
    api: ApiState,
    onOpenProvider: () -> Unit
) {
    val phase = track.phase
    val home = track.home
    if (phase == SealTrack.Phase.Idle || home.isUnspecified) return
    val colors = DaodianColors.current
    val density = LocalDensity.current
    val open = phase == SealTrack.Phase.Open
    BackHandler(enabled = open) { track.close() }

    val u = track.grow.value
    val pos = track.pos
    val dragging = track.dragging
    val level = (pos / SealTrack.GAP).coerceIn(0f, 4f)
    // 拖着时按位置连续变；别的时候就是选的那档
    val ink = if (dragging) Ink.at(level) else Ink.of(track.shown)
    val tint = if (look == SealLook.Down) colors.muted else colors.accent
    val homeX = with(density) { home.x.toDp() }
    val homeY = with(density) { home.y.toDp() }
    // 胶囊里的东西等胶囊长出一截再显
    val inside = ((u - 0.25f) / 0.75f).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        // 对话压暗。顶栏不压
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = homeY + 26.dp)
                .background(colors.paper.copy(alpha = 0.55f * u))
        )
        if (open) {
            Box(Modifier.fillMaxSize().pointerInput(track) { detectTapGestures { track.close() } })
        }

        // 连不上：胶囊左边压一条告警带，点它去配置页 —— 纸签没了，这是出事时去配置的地方
        (api as? ApiState.Down)?.takeIf { u > 0f }?.let { down ->
            Column(
                Modifier
                    .offset { IntOffset(12.dp.roundToPx(), (home.y - 22.dp.toPx()).roundToInt()) }
                    .width(homeX - 22.dp - 22.dp)
                    .alpha(u)
                    .background(colors.red.copy(alpha = 0.10f).compositeOver(colors.surface), RoundedCornerShape(10.dp))
                    .clickable { track.close(); onOpenProvider() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text("连不上 —— ${down.why}", style = DaodianType.rowTitle, color = colors.red, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(down.raw, style = DaodianType.basis, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            val x = home.x
            val half = 22.dp.toPx()
            val top = home.y - half
            val length = (44f + SealTrack.LAST * u).dp.toPx()
            val pill = Path().apply {
                addRoundRect(RoundRect(Rect(x - half, top, x + half, top + length), CornerRadius(half)))
            }
            // 浮起来：一圈淡影子
            drawIntoCanvas { canvas ->
                val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
                    isAntiAlias = true
                    color = colors.surface.toArgb()
                    setShadowLayer(16.dp.toPx(), 0f, 6.dp.toPx(), Color.Black.copy(alpha = 0.14f * u).toArgb())
                }
                canvas.nativeCanvas.drawPath(pill.asAndroidPath(), paint)
            }
            drawPath(pill, colors.surface)
            drawPath(pill, colors.rule2.copy(alpha = 0.6f + 0.4f * u), style = Stroke(1.dp.toPx()))

            clipPath(pill) {
                // 凹槽：比印窄，深一层，上沿和左沿压一道内阴影
                val g = 5.dp.toPx()
                val gTop = home.y - g
                val gBottom = home.y + SealTrack.LAST.dp.toPx() + g
                val groove = Path().apply {
                    addRoundRect(RoundRect(Rect(x - g, gTop, x + g, gBottom), CornerRadius(g)))
                }
                drawPath(groove, colors.ink.copy(alpha = 0.07f * inside))
                clipPath(groove) {
                    for ((w, a) in listOf(4f to 0.07f, 2f to 0.09f)) {
                        val wp = w.dp.toPx()
                        drawRoundRect(
                            Color.Black.copy(alpha = a * inside),
                            Offset(x - g + 0.6.dp.toPx() - wp / 2, gTop + 1.dp.toPx() - wp / 2),
                            Size(2 * g + wp, gBottom - gTop + wp),
                            CornerRadius(g + wp / 2),
                            style = Stroke(wp)
                        )
                    }
                    // 走过的那段染墨，越往下越浓
                    val inkY = home.y + pos.dp.toPx()
                    if (inkY > gTop) {
                        drawRect(
                            Brush.verticalGradient(
                                listOf(tint.copy(alpha = 0.10f * inside), tint.copy(alpha = maxOf(ink, 0.22f) * inside)),
                                startY = gTop, endY = inkY
                            ),
                            Offset(x - g, gTop), Size(2 * g, inkY - gTop)
                        )
                    }
                    // 刻度刻在槽底：五道短横线。染了墨的那段换成纸色，照样看得见
                    val inkLine = home.y + pos.dp.toPx()
                    for (i in 0..4) {
                        val y = home.y + SealTrack.tick(i).dp.toPx()
                        val c = if (y <= inkLine) colors.paper.copy(alpha = 0.75f) else colors.ink.copy(alpha = 0.20f)
                        drawRoundRect(
                            c.copy(alpha = c.alpha * inside),
                            Offset(x - 3.dp.toPx(), y - 0.6.dp.toPx()), Size(6.dp.toPx(), 1.2.dp.toPx()), CornerRadius(0.6.dp.toPx())
                        )
                    }
                }
                drawPath(groove, colors.rule2.copy(alpha = 0.5f * inside), style = Stroke(0.6.dp.toPx()))
            }
        }

        if (open) {
            // 胶囊上的手势：点哪档滑到哪；按住印或槽上拖；点印本身是不改、收起
            Box(
                Modifier
                    .offset { IntOffset((home.x - 22.dp.toPx()).roundToInt(), (home.y - 22.dp.toPx()).roundToInt()) }
                    .size(44.dp, (44f + SealTrack.LAST).dp)
                    .pointerInput(track) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            track.hold()
                            val y0 = down.position.y
                            val at = y0.toDp().value - 22f
                            val onSeal = abs(at - track.pos) < 18f
                            val start = if (onSeal) track.pos else at
                            var moved = false
                            var done = false
                            try {
                                while (true) {
                                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    if (!moved && abs(c.position.y - y0) > viewConfiguration.touchSlop) {
                                        moved = true
                                        track.grab()
                                    }
                                    if (moved) {
                                        c.consume()
                                        track.dragTo(start + (c.position.y - y0).toDp().value)
                                    }
                                }
                                done = true
                                when {
                                    moved -> track.drop()
                                    onSeal -> track.close()
                                    else -> track.pick(SealTrack.nearest(at))
                                }
                            } finally {
                                // 被系统打断：拖到哪算哪
                                if (!done && moved) track.drop()
                            }
                        }
                    }
            )
        }

        // 胶囊里的印
        val s = 1f + 0.06f * track.bump.value
        SealFace(
            tint = tint,
            ink = ink,
            bleed = Ink.bleed(if (dragging) level else track.shown.ordinal.toFloat()) + 4f * track.halo.value,
            shadow = true,
            modifier = Modifier
                .offset { IntOffset((home.x - 11.dp.toPx()).roundToInt(), (home.y + pos.dp.toPx() - 11.dp.toPx()).roundToInt()) }
                .graphicsLayer {
                    scaleX = s
                    scaleY = s
                }
        )
    }
}

package com.abc.daodian.shared.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 动效 token。见 DESIGN.md 决策 6.3，动效稿：
 * https://claude.ai/code/artifact/c0493995-43b7-4220-8a00-35adb5990804
 *
 * 所有时长和曲线只从这里取 —— 稿子里的 CSS 和这里一一对应，改一边就得改另一边。
 * 整个回合只有「落印」一个重拍，其余动效都往后退，别在这里再加花样。
 */
object Motion {

    /** 墨落：出现、展开、起稿、气泡升起。起得快、落得稳 */
    val Settle = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 行文：收起、折叠、变形、字的淡入 */
    val Flow = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 退场：擦掉、喊停、离场。越走越快，不拖泥带水 */
    val Exit = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /**
     * 容器变换：桌面小组件长成速记那张纸、再缩回去（DESIGN.md §8.3）。
     * 起步不冲、收尾很稳 —— 读得出「这一块在变大」。别拿 [Settle] 顶替：
     * 它三成时间就走完七成路，整块纸看着是蹦出来的，真机上试过。
     */
    val Expand = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** 一段增量从淡到实 */
    const val CHAR = 180
    const val SHORT = 160
    const val MID = 280
    const val LONG = 420

    /** 墨条上那道墨色洇过去一趟 */
    const val WASH = 1400

    /** 小组件长成纸 / 纸缩回小组件。收比放快一截：人要走的时候别拦着 */
    const val EXPAND = 460
    const val COLLAPSE = 340

    fun <T> settle(duration: Int = MID, delay: Int = 0): TweenSpec<T> = tween(duration, delay, Settle)
    fun <T> flow(duration: Int = MID, delay: Int = 0): TweenSpec<T> = tween(duration, delay, Flow)
    fun <T> exit(duration: Int = SHORT): TweenSpec<T> = tween(duration, 0, Exit)

    /** 盖印：只给「已记下」那一枚印用 —— 过冲到 0.9 再回 1 */
    fun <T> stamp(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = 380f)
}

package com.abc.daodian.agent.conversation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.abc.daodian.shared.theme.DaodianPalette
import com.abc.daodian.shared.theme.DarkPalette
import com.abc.daodian.shared.theme.LightPalette

/**
 * 模型画的图按当前主题上色（DESIGN.md §6.8「颜色」）。提示词让它不画背景、字和线用 currentColor、
 * 数据只用浅色色板里的几种；这里在交给 AndroidSVG 之前改一遍原文。
 */
internal object SvgColors {

    private val HEX = Regex("#[0-9a-fA-F]{6}(?![0-9a-fA-F])|#[0-9a-fA-F]{3}(?![0-9a-fA-F])")

    /** 颜色名只认写在属性值、样式值里的（fill="white"、color: black），图里文字里的 white 不动 */
    private val NAMED = Regex("([=:]\\s{0,2}[\"']?)(black|white)(?![\\w-])", RegexOption.IGNORE_CASE)
    private val CURRENT_COLOR = Regex("currentColor", RegexOption.IGNORE_CASE)
    private val ROOT = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ROOT_FILL = Regex("\\sfill\\s*=", RegexOption.IGNORE_CASE)

    fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    /**
     * 深色主题下的换色表：浅色色板里的换成深色色板对应的那个；
     * 模型不听话写的纯黑、深灰换成墨色，纯白换成纸色 —— 不然字会沉进深色的底里。
     */
    private val darkSwaps: Map<String, String> by lazy {
        val l = LightPalette
        val d = DarkPalette
        val palette = listOf(
            l.ink to d.ink, l.ink2 to d.ink2, l.muted to d.muted, l.hint to d.hint,
            l.rule to d.rule, l.rule2 to d.rule2, l.ruleSoft to d.ruleSoft, l.accent to d.accent,
            l.red to d.red, l.amber to d.amber, l.paper to d.paper, l.surface to d.surface, l.surfaceAlt to d.surfaceAlt
        ).associate { (a, b) -> hex(a).lowercase() to hex(b) }
        val ink = hex(d.ink)
        val paper = hex(d.paper)
        palette + listOf("#000000", "#000", "#111111", "#111", "#222222", "#222", "#333333", "#333", "black").associateWith { ink } +
            listOf("#ffffff", "#fff", "white").associateWith { paper }
    }

    /**
     * currentColor 换成墨色；根 <svg> 上没写 fill 的补一个墨色（SVG 默认填黑，深色下看不见）；
     * 深色主题再按 [darkSwaps] 换一遍。
     */
    fun themed(source: String, colors: DaodianPalette): String {
        val ink = hex(colors.ink)
        var s = CURRENT_COLOR.replace(source, ink)
        if (colors == DarkPalette) {
            s = HEX.replace(s) { m -> darkSwaps[m.value.lowercase()] ?: m.value }
            s = NAMED.replace(s) { m -> m.groupValues[1] + (darkSwaps[m.groupValues[2].lowercase()] ?: m.groupValues[2]) }
        }
        val root = ROOT.find(s) ?: return s
        if (ROOT_FILL.containsMatchIn(root.value)) return s
        return s.substring(0, root.range.first) + "<svg fill=\"$ink\"" + s.substring(root.range.first + 4)
    }
}

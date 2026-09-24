package com.abc.daodian.agent.conversation

import com.abc.daodian.shared.theme.DarkPalette
import com.abc.daodian.shared.theme.LightPalette
import org.junit.Assert.assertEquals
import org.junit.Test

/** 模型画的图按主题上色 */
class SvgColorsTest {

    private val lightInk = SvgColors.hex(LightPalette.ink)
    private val darkInk = SvgColors.hex(DarkPalette.ink)

    @Test
    fun `currentColor and the default fill become ink`() {
        assertEquals(
            """<svg fill="$lightInk" viewBox="0 0 10 10"><line stroke="$lightInk"/></svg>""",
            SvgColors.themed("""<svg viewBox="0 0 10 10"><line stroke="currentColor"/></svg>""", LightPalette)
        )
        // 根上自己写了 fill 就不补
        val own = """<svg viewBox="0 0 1 1" fill="none"><rect/></svg>"""
        assertEquals(own, SvgColors.themed(own, LightPalette))
    }

    @Test
    fun `light theme leaves the model's colors alone`() {
        val src = """<svg fill="none"><rect fill="#9E3B2E"/><text fill="#333">white</text></svg>"""
        assertEquals(src, SvgColors.themed(src, LightPalette))
    }

    @Test
    fun `dark theme swaps palette colors, black and white`() {
        val out = SvgColors.themed(
            """<svg fill="none"><rect fill="#9e3b2e"/><rect fill="${SvgColors.hex(LightPalette.rule2)}"/>""" +
                """<text fill="#333">white 还是 white</text><rect style="fill: white"/><rect fill="black"/><rect fill="#123456"/></svg>""",
            DarkPalette
        )
        assertEquals(
            """<svg fill="none"><rect fill="${SvgColors.hex(DarkPalette.accent)}"/><rect fill="${SvgColors.hex(DarkPalette.rule2)}"/>""" +
                """<text fill="$darkInk">white 还是 white</text><rect style="fill: ${SvgColors.hex(DarkPalette.paper)}"/>""" +
                """<rect fill="$darkInk"/><rect fill="#123456"/></svg>""",
            out
        )
    }

    @Test
    fun `eight digit colors and longer hex are not cut in half`() {
        val src = """<svg fill="none"><rect fill="#FFFFFF80"/><rect fill="#fffa"/></svg>"""
        assertEquals(src, SvgColors.themed(src, DarkPalette))
    }
}

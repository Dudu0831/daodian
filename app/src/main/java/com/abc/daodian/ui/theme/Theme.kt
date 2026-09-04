package com.abc.daodian.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalDaodianPalette = staticCompositionLocalOf { LightPalette }

/** `DaodianColors.current.green` 之类，随浅/深色主题自动切换 */
object DaodianColors {
    val current: DaodianPalette
        @Composable get() = LocalDaodianPalette.current
}

@Composable
fun DaodianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkPalette else LightPalette

    // 只喂给零星几个仍用 Material3 组件（TextField、Switch）的地方，
    // 界面主体都是直接读 DaodianColors 画的自定义 composable。
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.solid,
            onPrimary = palette.onSolid,
            background = palette.paper,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.ink2,
            outline = palette.rule2,
            error = palette.red
        )
    } else {
        lightColorScheme(
            primary = palette.solid,
            onPrimary = palette.onSolid,
            background = palette.paper,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.ink2,
            outline = palette.rule2,
            error = palette.red
        )
    }

    CompositionLocalProvider(LocalDaodianPalette provides palette) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

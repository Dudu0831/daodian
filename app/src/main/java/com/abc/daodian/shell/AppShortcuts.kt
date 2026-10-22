package com.abc.daodian.shell

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.abc.daodian.R
import com.abc.daodian.shared.navigation.Launch

/**
 * 长按桌面图标弹出来的那几项（系统的 App Shortcuts）。现在只有一项「拍一张」：开相机，拍回来排进对话页待发那一行。
 *
 * 用动态的、不写 res/xml/shortcuts.xml：静态的要写死包名，debug 包（`.debug`）和 release 包是两个 app，写不了一份。
 * 每次开 app 在前台重设一遍 —— 前台调不限次数，改了字下次打开就跟上。后面「应用信息」「小窗」那几项是 ROM 加的，不归这里。
 */
object AppShortcuts {

    private const val CAMERA = "camera"

    fun install(context: Context) {
        val camera = ShortcutInfoCompat.Builder(context, CAMERA)
            .setShortLabel("拍一张")
            .setLongLabel("拍一张，交给到点")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_camera))
            // 快捷方式的 intent 必须带 action
            .setIntent(Launch.intent(context, camera = true).setAction(Intent.ACTION_VIEW))
            .build()
        ShortcutManagerCompat.setDynamicShortcuts(context, listOf(camera))
    }
}

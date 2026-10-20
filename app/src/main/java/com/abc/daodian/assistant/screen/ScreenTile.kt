package com.abc.daodian.assistant.screen

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.abc.daodian.assistant.quick.QuickTrace

/**
 * 探路：下拉栏里那块「到点看一眼」。点一下让 [ScreenAssistService] 开一次会话读当前屏幕。
 * 还没被选成辅助应用（服务没被系统绑上）时是灰的，点它去「辅助应用和语音输入」那页。
 */
class ScreenTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (ScreenAssistService.active != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val service = ScreenAssistService.active
        QuickTrace.log(this, "screen tile click active=${service != null}")
        if (service != null) service.look() else openSettings()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openSettings() {
        val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}

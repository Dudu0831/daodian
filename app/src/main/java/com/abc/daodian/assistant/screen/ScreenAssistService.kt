package com.abc.daodian.assistant.screen

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.abc.daodian.assistant.quick.QuickTrace

/**
 * 探路：被选成「辅助应用」时系统一直绑着的那个服务。只用来让我们自己（[ScreenTile]）调 [showSession]，
 * 系统随会话把当前屏幕的文字和截图交给 [ScreenAssistSession]。
 *
 * 手势叫不出来（长按电源键是关机、长按圆圈只给 YOYO，见 CLAUDE.md 10-20），入口是自己的磁贴。
 * 没被选成助理时系统不绑它，[active] 就是 null。
 */
class ScreenAssistService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        active = this
        QuickTrace.log(this, "screen service ready")
    }

    override fun onShutdown() {
        QuickTrace.log(this, "screen service shutdown")
        if (active === this) active = null
        super.onShutdown()
    }

    /** 叫一次会话，要文字也要截图。两样给不给还看系统设置里「使用屏幕上的文字」「使用屏幕截图」两个开关 */
    fun look() {
        showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }

    companion object {
        @Volatile
        var active: ScreenAssistService? = null
            private set
    }
}

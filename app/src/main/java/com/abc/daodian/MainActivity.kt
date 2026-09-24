package com.abc.daodian

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.abc.daodian.agent.conversation.ChatViewModel
import com.abc.daodian.agent.entry.quick.WidgetFrame
import com.abc.daodian.agent.shell.AppNavHost
import com.abc.daodian.shared.navigation.Launch
import com.abc.daodian.shared.theme.DaodianTheme

/** 唯一的宿主 Activity：挂上 agent/shell 的导航，其余都在里面 */
class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    /**
     * 从小组件、通知、桌面速记点进来时要去的那一屏（[Launch]）。用完置空 ——
     * 不置空的话，转屏重组会把「去编辑第 7 条」再执行一遍。
     */
    private var request by mutableStateOf<Launch.Request?>(null)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = Launch.requestOf(intent)
        WidgetFrame.remember(this, intent)
        // 全屏绘制：窗口不再为键盘自己缩一次，inset 只有 Compose 这一个来源。
        // 少了这行，键盘弹起时窗口缩一遍、imePadding 再顶一遍，输入框会飞到半空。
        enableEdgeToEdge()

        // POST_NOTIFICATIONS 是唯一需要运行时申请的权限，见设计文档 §5.2
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            DaodianTheme {
                AppNavHost(
                    vm = vm,
                    request = request,
                    onRequestHandled = { request = null }
                )
            }
        }
        holdSplashUntilRestored()
    }

    /**
     * 开屏留到对话记录读回来再撤（最多 [SPLASH_HOLD_MAX_MS]）。不留的话，开屏撤掉那一刻历史往往还没读完，
     * 对话页先空一下、再冒出聊天记录 —— 看着就是「闪一下」。
     *
     * 系统开屏盖到窗口第一次画出来为止；第一帧的绘制按住不放，它就一直盖着（官方「让开屏多留一会儿」的做法）。
     * 转屏重建时 ViewModel 还在、早就读完了，第一帧直接放行。
     */
    private fun holdSplashUntilRestored() {
        // 在这儿就把 ViewModel 建出来：读库从这一刻开始，不用等到第一次组合
        val restored = vm.restored
        val content = requireViewById<View>(android.R.id.content)
        val since = SystemClock.uptimeMillis()
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val go = restored.value || SystemClock.uptimeMillis() - since > SPLASH_HOLD_MAX_MS
                if (go) content.viewTreeObserver.removeOnPreDrawListener(this)
                return go
            }
        })
    }

    /** launchMode 是 singleTop：app 已经开着的时候再点小组件，走的是这里而不是 onCreate */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = Launch.requestOf(intent)
        WidgetFrame.remember(this, intent)
    }

    private companion object {
        /** 读库再慢也不能一直盖着开屏：到点就撤，对话页自己先空着等（ChatScreen） */
        const val SPLASH_HOLD_MAX_MS = 1000L
    }
}

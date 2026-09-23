package com.abc.daodian.agent.entry.quick

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.abc.daodian.shared.theme.DaodianTheme
import com.abc.daodian.shared.navigation.WidgetLaunch
import com.abc.daodian.shared.navigation.WidgetTarget

/**
 * 桌面速记。小组件右下角那枚墨印点下去，拉起的是它，不是 MainActivity —— 见 DESIGN.md §8.3
 *
 * 透明窗口，桌面原样留在底下、不变色；一张纸从被点的墨印里长出来。独立 taskAffinity、
 * 不进最近任务：记完就走，不在后台留一个 app 页面。和 AlarmActivity 独立出来是同一个理由 ——
 * 「随手说一句」和「正常打开 app」互不污染。
 */
class QuickAddActivity : ComponentActivity() {

    private val vm: QuickAddViewModel by viewModels()

    /** 正在等麦克风授权弹窗。这期间被 stop 不算用户走开 */
    private var askingPermission = false

    /**
     * 整块小组件在屏幕上的框（桌面随点击给的 sourceBounds），见 [anchorOf]。
     * 纸从这里长出来、记完缩回这里；桌面不给位置就是 null，纸从屏幕底部升起。
     */
    private var anchor by mutableStateOf<Rect?>(null)

    /** 小组件上那枚墨印在屏幕上的位置。展开时它一路飞到纸中间 */
    private var mic by mutableStateOf<Rect?>(null)

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askingPermission = false
        if (granted) vm.startListening()
        else vm.blockVoice("没有麦克风权限。可以在系统设置里给「到点」打开，或者去 app 里说。")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进出场在 Compose 里自己画（纸从墨印里长出来），系统那一下窗口动画会和它叠在一起
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        enableEdgeToEdge()
        anchor = anchorOf(intent)
        mic = micOf(intent)
        QuickTrace.log(this, "activity onCreate sourceBounds=${intent.sourceBounds} restored=${savedInstanceState != null}")
        if (savedInstanceState == null) requestVoice()

        setContent {
            DaodianTheme {
                QuickAddScreen(
                    vm = vm,
                    anchor = anchor,
                    // 小组件的圆角是系统给的（widget_bg.xml 用的就是它），纸一开始要和它一模一样
                    anchorCorner = resources.getDimension(android.R.dimen.system_app_widget_background_radius),
                    mic = mic,
                    onVoice = ::requestVoice,
                    onClose = ::finish,
                    onEdit = { id -> openApp(WidgetTarget.Edit(id)) },
                    onManual = { openApp(WidgetTarget.New) },
                    onOpenApp = { openApp(WidgetTarget.Chat) },
                    onHandoff = { said -> openApp(if (said.isBlank()) WidgetTarget.Chat else WidgetTarget.Say(said)) }
                )
            }
        }
    }

    /** 纸还开着时又点了一下墨印：换个锚点，没在忙就重新开始听 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        anchor = anchorOf(intent)
        mic = micOf(intent)
        QuickTrace.log(this, "activity onNewIntent sourceBounds=${intent.sourceBounds}")
        if (!vm.aiBusy && !vm.listening) requestVoice()
    }

    override fun onResume() {
        super.onResume()
        QuickTrace.log(this, "activity onResume")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        QuickTrace.log(this, "activity windowFocus=$hasFocus")
    }

    /** 用户按了 Home、锁了屏：这张纸就不留了。转屏、等授权弹窗不算 */
    override fun onStop() {
        super.onStop()
        QuickTrace.log(this, "activity onStop changingConfig=$isChangingConfigurations askingPermission=$askingPermission")
        if (!isChangingConfigurations && !askingPermission) finish()
    }

    private fun requestVoice() {
        QuickTrace.log(
            this,
            "activity requestVoice available=${vm.voiceAvailable} " +
                "granted=${checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED}"
        )
        when {
            !vm.voiceAvailable -> vm.blockVoice("这台手机没有可用的语音识别，去 app 里说吧。")
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                vm.startListening()
            else -> {
                askingPermission = true
                askMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun openApp(target: WidgetTarget) {
        startActivity(WidgetLaunch.intent(this, target))
        finish()
    }

    /**
     * 纸从哪儿长出来：整块小组件的框。点击只挂在墨印上，sourceBounds 是墨印的框，
     * 整块从它的右下角倒推，宽高见 [WidgetFrame]。拿不到位置就是 null，纸从屏幕底部升起。
     */
    private fun anchorOf(intent: Intent?): Rect? =
        intent?.sourceBounds?.let { WidgetFrame.fromMic(this, it) }?.toCompose()

    /** 小组件右下角那枚墨印：桌面随点击给的就是它的框 */
    private fun micOf(intent: Intent?): Rect? = intent?.sourceBounds?.toCompose()

    private fun android.graphics.Rect.toCompose() =
        Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

package com.abc.daodian

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.abc.daodian.ui.DaodianNavHost
import com.abc.daodian.ui.MainViewModel
import com.abc.daodian.ui.theme.DaodianTheme
import com.abc.daodian.widget.WidgetLaunch
import com.abc.daodian.widget.WidgetTarget

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    /**
     * 从桌面小组件点进来时要去的那一屏。用完置空 ——
     * 不置空的话，转屏重组会把「去编辑第 7 条」再执行一遍。
     */
    private var widgetTarget by mutableStateOf<WidgetTarget?>(null)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetTarget = WidgetLaunch.targetOf(intent)
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
                DaodianNavHost(
                    vm = vm,
                    widgetTarget = widgetTarget,
                    onWidgetTargetHandled = { widgetTarget = null }
                )
            }
        }
    }

    /** launchMode 是 singleTop：app 已经开着的时候再点小组件，走的是这里而不是 onCreate */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetTarget = WidgetLaunch.targetOf(intent)
    }
}

package com.abc.daodian.shared.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 按 Activity 取 ViewModel：同一个 Activity 里各页、抽屉、设置组拿到的是同一份。
 * 默认的 `viewModel()` 按导航栈里的那一页分别建，数据会不同步。
 */
@Composable
inline fun <reified VM : ViewModel> activityViewModel(): VM =
    viewModel(viewModelStoreOwner = LocalContext.current.componentActivity())

fun Context.componentActivity(): ComponentActivity {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    error("不在 ComponentActivity 里")
}

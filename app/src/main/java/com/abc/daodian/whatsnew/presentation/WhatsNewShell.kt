package com.abc.daodian.whatsnew.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import com.abc.daodian.BuildConfig
import com.abc.daodian.shell.entry.AppNav
import com.abc.daodian.shell.entry.ShellEntry
import com.abc.daodian.whatsnew.WhatsNew
import com.abc.daodian.whatsnew.WhatsNewModule

/** 新功能弹窗给壳的：盖在 app 上面的那一层、诊断里一行。没有自己的页面 */
object WhatsNewShell : ShellEntry {

    override val id = WhatsNewModule.ID

    override val label = WhatsNewModule.LABEL

    override fun NavGraphBuilder.routes(nav: AppNav) {}

    @Composable
    override fun Overlay() {
        val context = LocalContext.current
        var release by remember { mutableStateOf(WhatsNew.pending(context)) }
        val r = release ?: return
        WhatsNewDialog(
            release = r,
            onClose = {
                WhatsNew.dismiss(context)
                release = null
            }
        )
    }

    override suspend fun diagnostics(context: Context): String =
        "看过到 ${WhatsNew.seen(context)}，这一版 ${BuildConfig.VERSION_CODE}"
}

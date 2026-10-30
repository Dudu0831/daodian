package com.abc.daodian.whatsnew

import com.abc.daodian.module.Module
import com.abc.daodian.whatsnew.presentation.WhatsNewShell

/**
 * 新功能弹窗的接头：只给壳（[WhatsNewShell]：盖在 app 上面的那一层、诊断里一行）。不跟模型打交道。
 * 这一版写了新功能、还没看过，打开 app 就弹一次；内容在 [Releases]。见 DESIGN.md §11.4
 */
object WhatsNewModule : Module {

    const val ID = "whatsnew"
    const val LABEL = "新功能"

    override val id = ID
    override val label = LABEL
    override val shell get() = WhatsNewShell
}

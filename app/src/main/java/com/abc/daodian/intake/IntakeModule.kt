package com.abc.daodian.intake

import android.content.Context
import com.abc.daodian.intake.assistant.IntakePlugin
import com.abc.daodian.intake.presentation.IntakeShell
import com.abc.daodian.module.Module

/**
 * 通知监听层的接头：给壳（[IntakeShell]：通知管家的页、规则页和收到的通知、设置首页那一行、「权限」页里通知使用权那一组），
 * 给助手的（[IntakePlugin]：在对话里建「听通知」规则，DESIGN.md §12.2），冷启动催系统把监听绑回来、叫一下通知管家。
 * 这一层只有这个文件和 `presentation/` 碰模块接头和壳，核心（[Intake]、[NoticeListenerService]）永远不碰。见 DESIGN.md §2.3
 */
object IntakeModule : Module {

    const val ID = "intake"
    const val LABEL = "通知管家"

    override val id = ID
    override val label = LABEL
    override val shell get() = IntakeShell
    override val assistant get() = IntakePlugin

    override fun onAppStart(context: Context) {
        Intake.rebind(context.applicationContext)
        // 上次没办完的待办、没交成的交接接着办
        NoticeHub.start(context)
    }
}

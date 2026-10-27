package com.abc.daodian.intake

import android.content.Context
import com.abc.daodian.BuildConfig
import com.abc.daodian.intake.assistant.IntakePlugin
import com.abc.daodian.intake.presentation.IntakeShell
import com.abc.daodian.module.Module

/**
 * 通知监听层的接头：给壳（[IntakeShell]：「权限与监听」页里的几组、设置首页那一行上的一句、勾 app 的页面），
 * 冷启动催系统把监听绑回来、叫一下通知管家（新链路开着时）。给助手的（[IntakePlugin]：在对话里建「听通知」规则，DESIGN.md §12.2）
 * 现在只进 debug 包：新链路第 6 步才接线，release 包上建了规则也不会响；切换那一版（NOTICE_PLAN 第 8 步）去掉这个判断。
 * 这一层只有这个文件和 `presentation/` 碰模块接头和壳，核心（[Intake]、[NoticeListenerService]）永远不碰。见 DESIGN.md §2.3
 */
object IntakeModule : Module {

    const val ID = "intake"
    const val LABEL = "通知监听"

    override val id = ID
    override val label = LABEL
    override val shell get() = IntakeShell
    override val assistant get() = if (BuildConfig.DEBUG) IntakePlugin else null

    override fun onAppStart(context: Context) {
        Intake.rebind(context.applicationContext)
        // 新链路开着时：上次没办完的待办、没交成的交接接着办
        NoticeHub.start(context)
    }
}

package com.abc.daodian.intake

import android.content.Context
import com.abc.daodian.intake.presentation.IntakeShell
import com.abc.daodian.module.Module

/**
 * 通知监听层的接头：只给壳（[IntakeShell]：「权限与监听」页里的几组、设置首页那一行上的一句、勾 app 的页面），
 * 冷启动催系统把监听绑回来。它不跟模型打交道 —— 没有给助手的那一份。
 * 这一层只有这个文件和 `presentation/` 碰模块接头和壳，核心（[Intake]、[NoticeListenerService]）永远不碰。见 DESIGN.md §2.3
 */
object IntakeModule : Module {

    const val ID = "intake"
    const val LABEL = "通知监听"

    override val id = ID
    override val label = LABEL
    override val shell get() = IntakeShell

    override fun onAppStart(context: Context) = Intake.rebind(context.applicationContext)
}

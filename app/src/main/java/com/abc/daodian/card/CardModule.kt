package com.abc.daodian.card

import android.content.Context
import com.abc.daodian.card.assistant.CardPlugin
import com.abc.daodian.card.plugin.CardBoard
import com.abc.daodian.card.presentation.CardShell
import com.abc.daodian.module.Module

/**
 * 桌面卡片的接头：模型拼一张卡片钉在桌面上，上面的数每次重画时从提醒、记账里现取。见 DESIGN.md §8.5
 *
 * 这个模块管通用的那一半：说明和闸门（`spec/`）、照说明拼（`render/`）、桌面组件（[CardWidget]）、登记（`data/`）、
 * 对话里的工具包（[CardPlugin]）。
 * 数从哪来由别的模块交（`plugin/CardSource`，接头上的 `Module.cards`），这里不 import 它们。
 * 给壳的只有导出诊断里一段（[CardShell]），没有页面。
 */
object CardModule : Module {

    const val ID = "card"
    const val LABEL = "桌面卡片"

    override val id = ID
    override val label = LABEL
    override val assistant get() = CardPlugin
    override val shell get() = CardShell

    /** 插口里那个「数据变了」接到这里的重画上（别的模块只认插口，不碰 [Cards]） */
    override fun onAppStart(context: Context) {
        CardBoard.install(Cards::refresh)
    }
}

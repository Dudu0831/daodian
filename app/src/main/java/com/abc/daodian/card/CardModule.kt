package com.abc.daodian.card

import com.abc.daodian.card.assistant.CardPlugin
import com.abc.daodian.module.Module

/**
 * 桌面卡片的接头：把一件事钉在桌面上，卡片自己每天数还剩几天。见 DESIGN.md §8.5
 *
 * 这个模块管通用的那一半：模板（[CardTemplate]）、桌面组件（[CardWidget]）、登记（`data/`）、对话里的工具包（[CardPlugin]）。
 * 钉的是什么由别的模块交（`plugin/CardSource`，接头上的 `Module.cards`），这里不 import 它们。没有页面，不给壳。
 */
object CardModule : Module {

    const val ID = "card"
    const val LABEL = "桌面卡片"

    override val id = ID
    override val label = LABEL
    override val assistant get() = CardPlugin
}

package com.abc.daodian.runtime.kit

import com.abc.daodian.runtime.tool.Tool

/**
 * 工具包：平时收着的一组工具，连同「这一组怎么用」。模型调 `open_kit` 打开，说明和工具一起来（[OpenKitTool]、[KitGate]）。
 * 见 DESIGN.md §6.11
 *
 * 工具和说明绑在这一个对象上，谁交工具谁写说明，不靠名字去对。只有说明、没有工具的也行（[tools] 为空）。
 * 常用的工具不往这里放：打开要多走一步，只有偶尔才用的才值得收。
 *
 * @param name 模型打开时填的名字，英文小写（「notice」）。一个 agent 手上不能重名
 * @param summary 目录上那一句：什么时候该开。模型只凭它决定开不开，写他会怎么说，不写这组工具是什么
 * @param guide 打开后回给模型的说明：这组工具怎么用、先后顺序、哪些要问
 * @param tools 打开后才带给模型的工具
 */
class Kit(val name: String, val summary: String, val guide: String, val tools: List<Tool> = emptyList()) {
    init {
        require(NAME.matches(name)) { "工具包的名字只用小写字母、数字、短横线：$name" }
        require(summary.isNotBlank() && guide.isNotBlank()) { "工具包「$name」的简介和说明都要写" }
    }

    private companion object {
        val NAME = Regex("[a-z][a-z0-9-]*")
    }
}

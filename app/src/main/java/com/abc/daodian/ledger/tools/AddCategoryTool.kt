package com.abc.daodian.ledger.tools

import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.engine.tool.ToolEffect
import com.abc.daodian.agent.engine.tool.ToolOutcome
import com.abc.daodian.ledger.domain.Actor
import com.abc.daodian.ledger.domain.CategoryKind
import com.abc.daodian.ledger.domain.LedgerBackend
import com.abc.daodian.ledger.domain.LedgerGuard
import com.abc.daodian.ledger.domain.LedgerText
import com.abc.daodian.ledger.tools.LedgerJson.text

/**
 * 加一个类别。二级类别整理时可以顺手建（record_expenses 里写个新名字就行），
 * 这个工具主要给「用户要一个新的一级类别」用 —— 一级是他在界面上看的，所以要问他。
 */
class AddCategoryTool(private val backend: LedgerBackend) : Tool {

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description =
        "加一个类别。只有用户明确要一个新的一级类别（比如「以后单独记宠物」）才用；二级类别归类时直接写新名字就会建。"

    override val parameters: Map<String, Any?> = LedgerJson.obj(
        "name" to LedgerJson.str("类别名，8 字以内"),
        "parent" to LedgerJson.strOrNull("挂在哪个一级下面；建一级就 null"),
        "kind" to LedgerJson.enumOf("OUT 支出类别 / IN 收入类别", CategoryKind.entries.map { it.name })
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val o = LedgerJson.parse(arguments) ?: return ToolOutcome("参数不是合法的 JSON", ok = false)
        val name = o.text("name")?.take(8) ?: return ToolOutcome("没建。name 必填", ok = false)
        val kind = CategoryKind.entries.firstOrNull { it.name == o.text("kind") } ?: CategoryKind.OUT
        val categories = backend.categories()
        val parentName = o.text("parent")
        val parent = parentName?.let { p ->
            categories.firstOrNull { it.isTop && it.kind == kind && it.name == p }
                ?: return ToolOutcome("没建。没有叫「$p」的一级类别。${LedgerText.categoryTree(categories)}", ok = false)
        }
        val siblings = categories.filter { it.parentId == parent?.id && it.kind == kind }
        if (siblings.any { it.name == name }) return ToolOutcome("「$name」已经有了，不用建。", ok = true)
        if (parent == null && siblings.size >= MAX_TOP) return ToolOutcome("一级类别已经有 ${siblings.size} 个了，不能再加。", ok = false)
        if (parent != null && siblings.size >= LedgerGuard.MAX_SUB_PER_TOP) {
            return ToolOutcome("「${parent.name}」下面已经有 ${siblings.size} 个二级了，不能再加。", ok = false)
        }
        val id = backend.addCategory(name, parent?.id, kind, Actor.USER)
        return ToolOutcome("建好了：${parent?.let { "${it.name}/" }.orEmpty()}$name", ok = true, ref = id)
    }

    companion object {
        const val NAME = "add_category"

        /** 一级类别（同一边）最多几个。一级是给人看的，多了就不是「大概」了 */
        const val MAX_TOP = 12
    }
}

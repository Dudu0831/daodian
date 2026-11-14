package com.abc.daodian.runtime.kit

import com.abc.daodian.runtime.context.Compaction
import com.abc.daodian.runtime.engine.Turn
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolSelector

/**
 * 工具包的门：这一步带哪些工具 = 不属于任何包的（常驻）+ 还开着的包里的。
 *
 * 「开着」不另存状态，从发出去的历史里看：最近 [keep] 轮里成功调过 `open_kit` 的包开着。更早的那次打开，
 * 说明已经被 [com.abc.daodian.runtime.context.fold.FoldKits] 折成一句，工具也跟着撤 —— 两边用同一个 [keep]，
 * 所以模型手上有工具时，用法一定还在眼前。被摘要盖掉的轮次不在 [select] 的 `sent` 里，那些包自然也收了。
 *
 * 打开、收起各让工具表变一次（前缀缓存失效一次），平时不动。
 */
class KitGate(private val kits: List<Kit>, private val keep: Int = KEEP) : ToolSelector {

    /** 工具名 → 它收在哪个包里 */
    private val home: Map<String, String> = kits.flatMap { kit -> kit.tools.map { it.name to kit.name } }.toMap()

    override fun select(all: Collection<Tool>, sent: List<Turn>): Collection<Tool> {
        val open = OpenKitTool.opened(sent.takeLast(keep))
        return all.filter { tool -> home[tool.name]?.let { it in open } ?: true }
    }

    override fun whyHidden(name: String): String = home[name]?.let {
        "没执行。$name 收在工具包「$it」里，现在没开着：先 ${OpenKitTool.NAME} 打开「$it」，看了用法再调。"
    } ?: super.whyHidden(name)

    companion object {
        /** 打开后管几轮（含打开的那一轮）。和压缩的保护区一样长 */
        const val KEEP = Compaction.KEEP
    }
}

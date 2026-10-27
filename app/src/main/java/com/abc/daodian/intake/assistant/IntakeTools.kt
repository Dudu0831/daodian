package com.abc.daodian.intake.assistant

import com.abc.daodian.intake.assistant.RuleJson.text
import com.abc.daodian.intake.core.rule.Rule
import com.abc.daodian.intake.core.rule.RuleCheck
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import kotlinx.coroutines.flow.first

/**
 * 助手建「听通知」规则的六个工具（DESIGN.md §12.2）。用法写在助手的提示词里（`AssistantPrompt` 的「听通知」一段）。
 * 只读的四个不留痕；建、停留痕（[IntakeTrace]）
 */
object IntakeTools {

    fun of(desk: RuleDesk): List<Tool> = listOf(
        ListAppsTool(desk), RecentSendersTool(desk), PreviewRuleTool(desk), ListRulesTool(desk), AddRuleTool(desk), StopRuleTool(desk)
    )

    const val LIST_APPS = "list_apps"
    const val RECENT_SENDERS = "recent_senders"
    const val PREVIEW_RULE = "preview_rule"
    const val LIST_RULES = "list_rules"
    const val ADD_RULE = "add_rule"
    const val STOP_RULE = "stop_rule"

    val NAMES = setOf(LIST_APPS, RECENT_SENDERS, PREVIEW_RULE, LIST_RULES, ADD_RULE, STOP_RULE)

    internal fun obj(vararg props: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(*props),
        "required" to props.map { it.first },
        "additionalProperties" to false
    )

    /** 「r5 · 快递到了提醒取 · 短信、菜鸟（含 取件码 / 驿站）→ 快递到了…」 */
    internal fun line(r: Rule, desk: RuleDesk): String = "r${r.id} · ${r.name} · ${r.hard.describe(desk.names)} → ${r.soft}"
}

internal class ListAppsTool(private val desk: RuleDesk) : Tool {
    override val name = IntakeTools.LIST_APPS
    override val effect = ToolEffect.READ
    override val description = "手机上装了哪些 app（桌面上有图标的）：名字和包名。建规则前用它找包名。"
    override val parameters = IntakeTools.obj(
        "query" to mapOf("type" to listOf("string", "null"), "description" to "按名字或包名找（「银行」「菜鸟」「mms」）；列全部就 null")
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val q = RuleJson.parse(arguments)?.text("query")?.trim()?.takeIf { it.isNotEmpty() }
        val apps = desk.apps().filter { q == null || it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }
        if (apps.isEmpty()) return ToolOutcome(if (q == null) "看不到装了哪些 app。" else "没有名字或包名含「$q」的 app。", ok = true)
        val shown = apps.take(LIMIT).joinToString("\n") { "${it.label} · ${it.pkg}" }
        val more = if (apps.size > LIMIT) "\n……还有 ${apps.size - LIMIT} 个，用 query 缩小。" else ""
        return ToolOutcome(shown + more, ok = true)
    }

    private companion object {
        const val LIMIT = 60
    }
}

internal class RecentSendersTool(private val desk: RuleDesk) : Tool {
    override val name = IntakeTools.RECENT_SENDERS
    override val effect = ToolEffect.READ
    override val description = "某个 app 最近发过通知的标题（聊天软件、短信的标题就是发消息的人），整理过格式、新的在前。按人听时，标题要照这里一字不差。"
    override val parameters = IntakeTools.obj("app" to mapOf("type" to "string", "description" to "包名"))

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val pkg = RuleJson.parse(arguments)?.text("app")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolOutcome("没查：app 要填包名。", ok = false)
        val titles = desk.recentTitles(pkg)
        if (titles.isEmpty()) {
            return ToolOutcome("最近没见过 ${desk.names.of(pkg)} 的通知（app 刚打开时是空的）。可以请他让那个人发一条再查，或者直接问他标题怎么写。", ok = true)
        }
        return ToolOutcome("${desk.names.of(pkg)} 最近的标题：" + titles.joinToString("、") { "「$it」" }, ok = true)
    }
}

internal class PreviewRuleTool(private val desk: RuleDesk) : Tool {
    override val name = IntakeTools.PREVIEW_RULE
    override val effect = ToolEffect.READ
    override val description = "拿一套硬条件对一遍现在通知栏和收件箱里最近的通知：对得上几条、各是哪个 app 的、标题举几个。建规则前用它看条件宽了还是窄了。"
    override val parameters = IntakeTools.obj(*RuleJson.HARD)

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val o = RuleJson.parse(arguments) ?: return ToolOutcome("没对：参数不是合法的 JSON。", ok = false)
        val draft = RuleJson.draftOf(o)
        if (draft.apps.none { it.isNotBlank() }) return ToolOutcome("没对：apps 至少要一个包名。", ok = false)
        return ToolOutcome(RuleJson.preview(draft.hard(), desk.samples(), desk.names) + "。", ok = true)
    }
}

internal class ListRulesTool(private val desk: RuleDesk) : Tool {
    override val name = IntakeTools.LIST_RULES
    override val effect = ToolEffect.READ
    override val description = "现在有哪些听通知的规则：编号、名字、硬条件、软规则、启用还是停了、进来过几条。"
    override val parameters = IntakeTools.obj()

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val rules = desk.rules.all().first()
        if (rules.isEmpty()) return ToolOutcome("还没有规则：现在一条通知都不听。", ok = true)
        val counts = desk.counts()
        return ToolOutcome(rules.joinToString("\n") { r ->
            IntakeTools.line(r, desk) + " · " + (if (r.enabled) "启用着" else "停了") + " · 进来过 ${counts[r.id] ?: 0} 条"
        }, ok = true)
    }
}

internal class AddRuleTool(private val desk: RuleDesk) : Tool {
    override val name = IntakeTools.ADD_RULE
    override val effect = ToolEffect.WRITE
    override val description = "建一条听通知的规则：硬条件决定哪些通知进来，软规则告诉后台的通知管家进来之后交给谁、怎么办。问清楚了再建。"
    override val parameters = IntakeTools.obj(
        "name" to mapOf("type" to "string", "description" to "规则的名字，几个字：「快递到了提醒取」「招行记账」"),
        *RuleJson.HARD,
        "soft" to mapOf(
            "type" to "string",
            "description" to "软规则：进来之后交给谁（记账 / 提醒）、怎么办。写到不用猜：钟点写成 18:00，建成提醒还是当天事项，轻提醒只在他明说时写"
        )
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val o = RuleJson.parse(arguments) ?: return ToolOutcome("没建：参数不是合法的 JSON。", ok = false)
        val draft = RuleJson.draftOf(o)
        val installed = desk.apps().map { it.pkg }.toSet()
        val active = desk.rules.enabled().size
        val problems = RuleCheck.problems(draft, installed, active)
        if (problems.isNotEmpty()) return ToolOutcome("没建：${problems.joinToString("；")}。", ok = false)
        val hard = draft.hard()
        val rule = desk.rules.add(draft.name.trim(), hard, draft.soft.trim(), context.now.toInstant().toEpochMilli())
        val preview = RuleJson.preview(hard, desk.samples(), desk.names)
        // 已经挂在通知栏里、对得上的，当场进收件箱
        desk.sweep()
        return ToolOutcome(
            "建好了：r${rule.id}「${rule.name}」。\n${hard.describe(desk.names)} → ${rule.soft}\n$preview",
            ok = true,
            ref = rule.id
        )
    }
}

internal class StopRuleTool(private val desk: RuleDesk) : Tool {
    override val name = IntakeTools.STOP_RULE
    override val effect = ToolEffect.WRITE
    override val description = "停一条规则（不删，设置页上能撤销）。改规则 = 停旧的、建新的。"
    override val parameters = IntakeTools.obj("id" to mapOf("type" to "integer", "description" to "规则 r 编号的数字：r5 填 5"))

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val id = RuleJson.parse(arguments)?.get("id")?.takeIf { it.canConvertToLong() }?.asLong()
            ?: return ToolOutcome("没停：id 要填 r 编号的数字。", ok = false)
        val rule = desk.rules.all().first().firstOrNull { it.id == id } ?: return ToolOutcome("没停：没有 r$id 这条规则，先用 list_rules 查。", ok = false)
        if (!rule.enabled) return ToolOutcome("没停：r$id「${rule.name}」本来就停着。", ok = false)
        desk.rules.setEnabled(id, enabled = false, at = context.now.toInstant().toEpochMilli())
        return ToolOutcome("停了：r$id「${rule.name}」。", ok = true, ref = id)
    }
}

package com.abc.daodian.intake.core.rule

import com.abc.daodian.intake.core.format.tidy

/** 建规则落库前代码核的几样（DESIGN.md §12.2 的闸）。不过的退回给模型，一条一句写清哪里不对 */
object RuleCheck {

    /** 启用着的规则最多几条 */
    const val MAX_RULES = 30

    /** 含 / 不含的词至少几个字：一个字的词什么都中 */
    const val MIN_WORD = 2

    /**
     * @param installed 桌面上有图标的 app 的包名（`AppCatalog.launchable`）
     * @param active 现在启用着几条
     * @return 不过的理由；空的就是过了
     */
    fun problems(draft: RuleDraft, installed: Set<String>, active: Int): List<String> = buildList {
        if (active >= MAX_RULES) add("规则满了（最多 $MAX_RULES 条），先停掉用不着的")
        if (draft.name.isBlank()) add("name 是空的")
        val apps = draft.apps.mapNotNull { it.tidy() }
        if (apps.isEmpty()) add("apps 至少要一个包名")
        val missing = apps.filter { it !in installed }
        if (missing.isNotEmpty()) add("这些包名不在桌面上：${missing.joinToString("、")}（用 list_apps 查）")
        if (draft.title != null && draft.title.isBlank()) add("title 是空的：不按标题筛就别传")
        if (draft.startsWith != null && draft.startsWith.isBlank()) add("starts_with 是空的：没有暗号就别传")
        val short = (draft.bodyAny + draft.bodyNone).mapNotNull { it.tidy() }.filter { it.length < MIN_WORD }
        if (short.isNotEmpty()) add("含 / 不含的词至少 $MIN_WORD 个字：${short.joinToString("、") { "「$it」" }}")
        if (draft.soft.isBlank()) add("soft 是空的：软规则要写清交给谁、怎么办")
    }
}

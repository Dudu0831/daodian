package com.abc.daodian.intake.core.rule

import com.abc.daodian.intake.core.format.tidy

/**
 * 助手建规则（`add_rule`）交来的样子，和工具参数一一对应（DESIGN.md §12.2）。
 * 先过 [RuleCheck]，再 [hard] 拼成硬条件
 *
 * @param title 按标题筛，不筛是 null；[titleExact] 是「等于」，否则是「包含」
 * @param startsWith 暗号，没有是 null
 */
data class RuleDraft(
    val name: String,
    val apps: List<String>,
    val title: String? = null,
    val titleExact: Boolean = true,
    val bodyAny: List<String> = emptyList(),
    val bodyNone: List<String> = emptyList(),
    val startsWith: String? = null,
    val soft: String
) {
    /** 拼成硬条件。顺序固定：app → 标题 → 暗号 → 含 → 不含，痕上照这个念；词去空白、去重 */
    fun hard(): AllOf = AllOf(buildList {
        add(AppIn(apps.mapNotNull { it.tidy() }.toCollection(LinkedHashSet())))
        title.tidy()?.let { add(TitleIs(it, titleExact)) }
        startsWith.tidy()?.let { add(BodyStartsWith(it)) }
        words(bodyAny)?.let { add(BodyHasAny(it)) }
        words(bodyNone)?.let { add(BodyHasNone(it)) }
    })

    private fun words(list: List<String>): List<String>? = list.mapNotNull { it.tidy() }.distinct().ifEmpty { null }
}

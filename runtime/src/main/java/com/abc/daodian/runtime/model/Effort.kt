package com.abc.daodian.runtime.model

/**
 * 想多久：发给网关的 reasoning effort。对话页按住印往下拉选（DESIGN.md §8.4），全局一个，后台的 agent 也照它。
 * 五档从浅到深排，[maxOf] 能直接比。网关还收 minimal、xhigh、ultra，没放 —— 五档够分，再多拉不准。
 */
enum class Effort(val wire: String) {
    /** 快答：不想 */
    NONE("none"),
    LOW("low"),
    /** 默认 */
    MEDIUM("medium"),
    HIGH("high"),
    MAX("max");

    companion object {
        val DEFAULT = MEDIUM

        /** 存的是 [wire]；认不出来当默认 */
        fun parse(wire: String?): Effort = entries.firstOrNull { it.wire == wire } ?: DEFAULT

        /** 这一步实际用哪一档：选的和工具包要求的（[LlmRequest.effortFloor]）取深的 */
        fun of(chosen: Effort, floor: Effort?): Effort = if (floor == null) chosen else maxOf(chosen, floor)
    }
}

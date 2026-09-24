package com.abc.daodian.agent.engine.ask

import com.abc.daodian.agent.engine.Item

/**
 * 问卡要问的东西：模型拿不准时先猜好几个答案，让用户点一下，或者自己说。见 DESIGN.md §6.6
 *
 * 一张卡 = 一次 `ask_user` 调用，最多 [AskUserTool.MAX_QUESTIONS] 题。
 */
data class AskRequest(
    /** 卡头上的短标签：「4 笔」「交房租」 */
    val label: String,
    val questions: List<AskQuestion>
) {
    /** 只有一题：猜测画成整行的选项，点了就算答，没有「就这样」 */
    val single: Boolean get() = questions.size == 1
}

data class AskQuestion(
    /** 出处，一行小字：「9月22日 11:02 · 建行 · 没有商户」 */
    val context: String?,
    /** 问的是一笔钱时右上角的宋体金额：「¥36.50」 */
    val amount: String?,
    /** 问句：「什么时候提醒？」。问账时有金额就可以没有 */
    val prompt: String?,
    /** 第一个猜测的依据：「前两天 11 点多也各有一笔三十来块，你都说是午饭」 */
    val hint: String?,
    /** 猜测，最可能的在前 */
    val options: List<AskOption>
)

data class AskOption(
    /** 猜测本身：「午饭」「每个月最后一天」 */
    val label: String,
    /** 补一句：归到哪类、几点、只这一次还是每月 */
    val detail: String?
)

/** 用户怎么答的 */
sealed interface AskAnswer {

    /** 逐题的答案，和 [AskRequest.questions] 一一对应；没点的是 null（「先放着」） */
    data class Picked(val picks: List<Pick?>) : AskAnswer

    /** 一颗没点，在输入框里直接说了一句 —— 给整张卡的，由模型去对哪题是哪题 */
    data class Said(val text: String) : AskAnswer

    /** 没答：叫停了，或者 app 在等的时候被杀了 */
    data object Unanswered : AskAnswer
}

/** 一题的答案 */
sealed interface Pick {
    /** 点了第几个猜测 */
    data class Option(val index: Int) : Pick

    /** 点了「其他…」自己写的 */
    data class Typed(val text: String) : Pick
}

/**
 * 把问卡交给用户、等他答。界面实现它：亮出问卡，挂起到用户点完或者说完。
 * 没有界面的地方（后台整理）用 [NONE]，也就不该把 `ask_user` 给那个 agent。
 */
fun interface Asker {
    suspend fun ask(call: Item.ToolCall, request: AskRequest): AskAnswer

    companion object {
        val NONE = Asker { _, _ -> AskAnswer.Unanswered }
    }
}

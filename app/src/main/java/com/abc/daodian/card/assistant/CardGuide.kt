package com.abc.daodian.card.assistant

import com.abc.daodian.card.own.OwnData
import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.spec.CardSpecs

/**
 * 卡片工具包打开后给模型的说明（§6.11）：怎么用这几个工具、说明怎么写、能绑什么。能绑的那一段由各来源交的现拼，
 * 加一个模块不用改这里。说明的写法和 `spec/CardSpecs` 的解析是一份东西的两面，改一边就得改另一边（`CardGuideTest` 拿例子过闸门）
 */
object CardGuide {

    /** 喝水：卡片自己记的数、＋1 按钮、每天清零 */
    const val WATER = """{"name":"喝水","size":"2x2","own":{"cups":{"count":0,"max":8,"reset":"daily"}},"body":{"column":[""" +
        """{"row":[{"text":"喝水","style":"title","grow":true},{"text":"今天","style":"caption"}]},""" +
        """{"number":"{{own.cups}}","after":"/ 8 杯","accent_when":"{{own.cups.full}}","grow":true},""" +
        """{"dots":"{{own.cups}}","of":8},""" +
        """{"row":[{"text":"还差 {{own.cups.left}} 杯","style":"caption","grow":true},{"button":"＋1","do":"own.add cups 1"}]}]}}"""

    /** 倒数一条提醒：标题、还剩几天（最后一周朱砂、过了变灰）、那天的日期 */
    const val COUNTDOWN = """{"name":"牛奶过期","size":"2x2","body":{"column":[""" +
        """{"text":"{{reminder.title #7}}","style":"title"},""" +
        """{"number":"{{reminder.days_left #7}}","after":"天","size":"l","accent_when":"{{reminder.near #7}}","muted_when":"{{reminder.past #7}}","grow":true},""" +
        """{"text":"{{reminder.date #7}}","style":"caption"}]}}"""

    fun guide(sources: List<CardSource>): String = """
桌面卡片：在他的手机桌面上放一张卡片。卡片长什么样由你拼（下面的零件），上面的数由代码每次重画时按你写的绑定现取，跨了零点自己变 —— 你只写绑哪个，不抄数。

怎么做
- 他说想在桌面上看什么，你拼一份说明调 design_card（新画的 card_id 填 null）。对话里会自动出预览，不用描述它长什么样，一句话说画了什么就行。
- 他说哪里要改：用 list_cards 拿到那张现在的说明，整份改好再调 design_card（card_id 填它的编号）。
- 他说放到桌面（或者他自己点了预览底下的「放到桌面」）：pin_card。桌面会弹框，他点「添加」才出现，照结果里的话告诉他下一步，不要说「已经放好了」。
- 卡片自己记的数（喝了几杯、清单上的几项）：他在对话里说「喝了一杯」「清单加个葱」，用 change_card_data，不用重画。
- 卡片你拿不掉：他说不要了，告诉他长按那张卡片移除。他没说要放桌面，不要主动画。

说明的写法：一段 JSON，整段作为字符串填进 spec
{"name":"几个字","size":"2x2","own":{…},"body":{"column":[零件, …]}}
- size：2x2（方的，默认）、4x1（一长条）、4x2、4x4。2×2 只放得下一个标题、一个大数字、一两行小字；4×1 只放得下一行字加一根墨条。放不下的会被卡片边裁掉，宁少勿多。
- 零件，每个是一个对象，按它带的键认：
  {"column":[…]} 竖排　{"row":[…]} 横排 —— 能互相套，最多 ${CardSpecs.MAX_DEPTH - 1} 层；一张最多 ${CardSpecs.MAX_BLOCKS} 个零件
  {"text":"字","style":"title|body|caption","align":"start|center|end"}
  {"number":"{{…}}","before":"过","after":"天","size":"s|m|l|xl"}
  {"bar":"{{0 到 100 的数}}","left":"左头的字","right":"右头的字"}
  {"list":"{{一组行}}","max":3}
  {"bars":"{{一组数}}","labels":true,"height":44}
  {"checklist":"own 里一张 list 的键","max":5}
  {"dots":"{{一个数}}","of":8}
  {"timer":"own 里一个 timer 的键"}
  {"button":"＋1","do":"动作"}；图标按钮 {"button":"","icon":"mic|check","do":"动作"}
  {"rule":true} 一根细线
  任何零件加 "grow":true = 撑满它那一排剩下的地方（横排里用它把右边的字推到最右）。
- 颜色只有墨（默认）、灰、朱砂，不写颜色值。text、number 能写 "color":"muted|accent"，或者 "accent_when":"{{一个是/否}}"、"muted_when":"{{一个是/否}}" 跟着数据变。
- 字里能嵌绑定：「还差 {{own.cups.left}} 杯」。number、bar、dots 要么整个是一个 {{绑定}}，要么是一个数。

卡片自己记的（own），键用小写字母、数字、下划线：
  {"cups":{"count":0,"max":8,"reset":"daily"}} 一个数，reset 写 daily 就每天零点清零。绑 own.cups、own.cups.left、own.cups.full、own.cups.max
  {"items":{"list":["鸡蛋","番茄"]}} 一张清单。绑 own.items（配 checklist 或 list）、own.items.done、.total、.left、.all_done
  {"goal":{"text":"一句话"}} 绑 own.goal
  {"spring":{"date":"2027-02-06"}} 一个日子，只想在桌面上数日子、不用到点提醒时用它。绑 own.spring（那天的字）、own.spring.days_left、.days_past、.is_today、.near、.past
  {"noodle":{"timer":180}} 走秒，默认几秒。用 timer 零件显示，绑 own.noodle.running
  动作：${OwnData.ACTIONS.joinToString("、") { "own.${it.name} ${it.args}" }}

能绑的（写在 {{ }} 里，种类不对会被拦下）
${sources.joinToString("\n") { s -> "· ${s.label}（${s.id}）：${s.brief}\n" + s.fields.joinToString("\n") { f -> "  {{${s.id}.${f.name}${if (f.args.isEmpty()) "" else " " + f.args}}} ${f.kind.label} · ${f.what}" } }}

能点的（button 的 do、列表行尾的圈）
${sources.flatMap { s -> s.actions.map { a -> "  ${s.id}.${a.name} ${a.args} · ${a.what}" } }.joinToString("\n").ifEmpty { "  （只有 own 的那几个）" }}

两个例子
喝水：$WATER
倒数一条提醒（#7 换成真的编号）：$COUNTDOWN
""".trimIndent()
}

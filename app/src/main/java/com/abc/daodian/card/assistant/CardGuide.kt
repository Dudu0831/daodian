package com.abc.daodian.card.assistant

import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpecs

/**
 * 卡片工具包打开后给模型的说明（§6.11）：怎么用这几个工具、说明怎么写、能绑什么、几张样板。能绑的那一段由各来源交的现拼，
 * 加一个模块不用改这里。说明的写法和 `spec/CardSpecs` 的解析是一份东西的两面，改一边就得改另一边（`CardTest` 拿样板过闸门）。
 * 样板是设计稿 11-16 那九张（<https://claude.ai/artifact/4jMt2UAd4d9UvN369bo7b2>），编号是假的，照着改
 */
object CardGuide {

    /** 样板：名字 → 说明。#12 去旧金山（带行李清单）、#15 泡面（三分钟后响）、#8 交房租（每月）、#20 周六去超市（带清单） */
    val SAMPLES: List<Pair<String, String>> = listOf(
        "倒数" to """{"name":"去旧金山","size":"2x2","body":{"column":[""" +
            """{"text":"{{reminder.title #12}}","style":"title"},""" +
            """{"number":"{{reminder.days_left #12}}","after":"天","size":"l","accent_when":"{{reminder.near #12}}","muted_when":"{{reminder.past #12}}","grow":true},""" +
            """{"text":"{{reminder.date #12}} {{reminder.weekday #12}}","style":"caption"}]}}""",
        "计时（一条三分钟后响的提醒）" to """{"name":"泡面","size":"2x2","body":{"column":[""" +
            """{"text":"泡面","style":"title"},{"timer":"{{reminder.fire_at #15}}","grow":true},""" +
            """{"text":"{{reminder.time #15}} 会响","style":"caption"}]}}""",
        "倒数 + 行李清单" to """{"name":"去旧金山","size":"4x2","body":{"row":[""" +
            """{"column":[{"text":"{{reminder.title #12}}","style":"title"},""" +
            """{"number":"{{reminder.days_left #12}}","after":"天","accent_when":"{{reminder.near #12}}","grow":true},""" +
            """{"text":"{{reminder.date #12}} {{reminder.weekday #12}}","style":"caption"}]},""" +
            """{"column":[{"text":"还剩 {{reminder.items_left #12}} 样","style":"caption"},""" +
            """{"checklist":"{{reminder.items #12}}","max":4,"grow":true}],"grow":true}]}}""",
        "重复的" to """{"name":"交房租","size":"4x1","body":{"row":[""" +
            """{"column":[{"text":"{{reminder.title #8}}","style":"title"},""" +
            """{"text":"{{reminder.repeat #8}} · 下次 {{reminder.date #8}}","style":"caption"}],"grow":true},""" +
            """{"number":"{{reminder.days_left #8}}","after":"天","size":"s","accent_when":"{{reminder.near #8}}"}]}}""",
        "提醒、记账拼一张" to """{"name":"今天","size":"4x2","body":{"row":[""" +
            """{"column":[{"text":"今天","style":"title"},{"number":"{{reminder.today_count}}","after":"件","grow":true},""" +
            """{"text":"下一件 {{reminder.next_title}} · {{reminder.next_in}}","style":"caption"}],"grow":true},""" +
            """{"column":[{"text":"花了","style":"title"},{"number":"{{ledger.spent today}}","after":"元","grow":true},""" +
            """{"text":"{{ledger.count today}} 笔 · {{ledger.pending}} 笔等你对","style":"caption"}],"grow":true}]}}""",
        "这周花了" to """{"name":"这周花了","size":"4x2","body":{"row":[""" +
            """{"column":[{"text":"这周花了","style":"title"},{"number":"{{ledger.spent week}}","before":"¥","size":"s","grow":true},""" +
            """{"text":"上周 ¥{{ledger.spent last_week}}","style":"caption"}]},""" +
            """{"bars":"{{ledger.daily week}}","grow":true}]}}""",
        "钱花哪了" to """{"name":"这个月","size":"4x2","body":{"row":[""" +
            """{"column":[{"text":"这个月","style":"title"},{"number":"{{ledger.spent month}}","before":"¥","size":"s","grow":true},""" +
            """{"text":"平均每天 ¥{{ledger.per_day month}}","style":"caption"}]},""" +
            """{"list":"{{ledger.by_category month}}","max":3,"grow":true}]}}""",
        "买菜清单（一条周六去超市的提醒带清单）" to """{"name":"买菜","size":"4x4","body":{"column":[""" +
            """{"column":[{"text":"{{reminder.title #20}}","style":"title"},""" +
            """{"text":"{{reminder.date #20}} · 还有 {{reminder.days_left #20}} 天","style":"caption"}]},""" +
            """{"rule":true},{"checklist":"{{reminder.items #20}}","max":8,"grow":true},""" +
            """{"row":[{"text":"还剩 {{reminder.items_left #20}} 样","style":"caption","grow":true},{"button":"","icon":"check","do":"reminder.done #20"}]}]}}""",
        "今天要做" to """{"name":"今天要做","size":"4x2","body":{"column":[""" +
            """{"row":[{"text":"今天","style":"title","grow":true},{"text":"{{time.date}} {{time.weekday}}","style":"caption"}]},""" +
            """{"list":"{{reminder.today}}","max":3,"grow":true}]}}"""
    )

    fun guide(sources: List<CardSource>): String = """
桌面卡片：在他的手机桌面上放一张卡片。卡片长什么样由你拼（下面的零件），上面的数由代码每次重画时按你写的绑定现取，跨了零点自己变 —— 你只写绑哪个，不抄数。

先有记录，才有卡片
- 卡片自己什么都不记。每个数、每个能点的地方，都绑提醒或记账里真有的那条。
- 要数的那件事还没记成提醒：先 create_reminder 建一条（要清单就顺手带上 items），再画。只想在桌面上数日子、不用到点响铃的（春节），建成 ring=notice 的轻提醒。
- 哪个模块都不记的（每天喝了几杯水）：照实告诉他现在画不了，不要拿别的凑。
- 卡片上的东西要改（勾清单、改日子、加一样），去改那条提醒（change_checklist、update_reminder），卡片跟着变，不用重画。

怎么做
- 他说想在桌面上看什么，你拼一份说明调 design_card（新画的 card_id 填 null）。对话里会自动出预览，不用描述它长什么样，一句话说画了什么就行。
- 他说哪里要改样子：用 list_cards 拿到那张现在的说明，整份改好再调 design_card（card_id 填它的编号）。
- 他说放到桌面（或者他自己点了预览底下的「放到桌面」）：pin_card。桌面会弹框，他点「添加」才出现，照结果里的话告诉他下一步，不要说「已经放好了」。
- 卡片你拿不掉：他说不要了，告诉他长按那张卡片移除。绑的那条提醒删了，卡片会变成一行灰字，也是长按移除。他没说要放桌面，不要主动画。

说明的写法：一段 JSON，整段作为字符串填进 spec
{"name":"几个字","size":"2x2","body":{"column":[零件, …]}}
- size：${CardSize.entries.joinToString("、") { "${it.key}（最多 ${it.maxBlocks} 个零件）" }}，零件数连 column / row 一起算。2×2 只放得下一个标题、一个大数字、一两行小字；4×1 只放得下一行字加一个小数字。他在桌面上拖大拖小时，代码照实际大小收放：列表、清单多放或少放几行，数字放大缩小，实在放不下先收掉小字、细线这些 —— 所以按这个大小排好就行，宁少勿多。
- 零件，每个是一个对象，按它带的键认：
  {"column":[…]} 竖排　{"row":[…]} 横排 —— 能互相套，最多 ${CardSpecs.MAX_DEPTH - 1} 层
  {"text":"字","style":"title|body|caption","align":"start|center|end"}
  {"number":"{{数}}","before":"¥","after":"天","size":"s|m|l|xl"} size 是最大到多大，不写由代码按地方定（最大到 l）
  {"bar":"{{0 到 100 的数}}","left":"左头的字","right":"右头的字"}
  {"list":"{{一组行}}","max":3}
  {"checklist":"{{一组带方框的行}}","max":5} 点方框就勾 / 取消那一样；放不下的自己在最后写「还有 N 样」，不用再写一行
  {"bars":"{{一组数}}","labels":true,"height":44}
  {"dots":"{{一个数}}","of":8}
  {"timer":"{{一个时刻}}"} 一秒一秒往下数到那一刻
  {"button":"字","do":"动作"}；图标按钮 {"button":"","icon":"mic|check","do":"动作"}
  {"rule":true} 一根细线
  任何零件加 "grow":true = 撑满它那一排剩下的地方（横排里用它把右边的字推到最右）。
- number、bar、dots、list、checklist、bars、timer 的值只能是一个 {{名字}}：不能填数、不能和字拼。
- text 可以自己写字当标签（「去旧金山」「这周花了」「会响」），也能嵌 {{名字}}。不要把数抄进字里，数一律绑。
- 绑定里不写算式。要的数没有现成的名字，就是画不了那一样，换一个有的。
- 至少绑一条提醒或记账里的东西；只绑了 time 的卡片不收。
- 颜色只有墨（默认）、灰、朱砂，不写颜色值。text、number 能写 "color":"muted|accent"，或者 "accent_when":"{{一个是/否}}"、"muted_when":"{{一个是/否}}" 跟着数据变（最后 7 天朱砂、过了变灰）。

能绑的（写在 {{ }} 里，种类不对会被拦下）
${sources.joinToString("\n") { s -> "· ${s.label}（${s.id}）：${s.brief}\n" + s.fields.joinToString("\n") { f -> "  {{${s.id}.${f.name}${if (f.args.isEmpty()) "" else " " + f.args}}} ${f.kind.label} · ${f.what}" } }}

能点的（button 的 do；列表、清单每一行自带的不用写）
${sources.flatMap { s -> s.actions.map { a -> "  ${s.id}.${a.name}${if (a.args.isEmpty()) "" else " " + a.args} · ${a.what}" } }.joinToString("\n")}

样板（编号是假的，换成真的；照着改，不用照抄）
${SAMPLES.joinToString("\n") { (what, json) -> "$what：$json" }}
""".trimIndent()
}

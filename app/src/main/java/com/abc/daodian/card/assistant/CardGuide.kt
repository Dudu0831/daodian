package com.abc.daodian.card.assistant

import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.spec.CardSize

/**
 * 卡片工具包打开后给模型的说明（§6.11）：怎么用这几个工具、网页怎么写、怎么画得好看、能绑什么、两张样板（[CardSamples] 里的出门带清单、钱花哪了）。
 * 能绑的那一段由各来源交的现拼，加一个模块不用改这里。网页的外壳、变量、`D` 的样子在 `render/CardPage`，两边一起改。
 * 11-20 精简过：原来六张样板 + 长规矩约 1.8 万字，砍到约 9 千字，模拟器上同五句话两版画得差不多，留了短的。
 * 规矩只写闸门和截图管不到的；裁边、压字、绑错这些由退回的话告诉它
 */
object CardGuide {

    fun guide(sources: List<CardSource>): String = """
桌面卡片：你写一小页网页，代码在后台截成图贴到他的手机桌面上。上面的数你只写绑哪个，代码每次重画时现取、填进去。

- 数都要绑提醒或记账里真有的那条。要数的事还没记：先 create_reminder 建一条（只想数日子的建成 ring=notice），再画。哪儿都不记的（喝了几杯水）照实说画不了。
- 写好调 design_card（新画的 card_id 填 null；改旧的先 list_cards 拿网页再整份交）。画坏了（裁边、压字、报错）会退回给你改，不用问他。预览自动出现，一句话说画了什么就行。放上桌面由他点预览底下的「放到桌面 ›」，你做不了。

网页
- 只写 div、style、script。页面就是卡片大小：${CardSize.entries.joinToString("、") { "${it.key} 是 ${it.widthDp}×${it.heightDp}px" }}；最外层 height:100%、用 flex 排，别写死宽高。
- 是一张静止的图、断网：没有动画，外面的图和字体拉不进来，图标用内嵌 svg 画，不用 emoji。
- 颜色只用变量：--paper 底 --ink 字 --ink2 --muted --hint --rule --rule2 --accent（朱砂）；字体 var(--serif)、var(--sans)。
- 垫在后面的装饰加 data-bg（绝对定位的可以压出边）。字和字别互相压住，数字会变长要留地方。

画好看
- 一个主角（通常是一个数），var(--serif) 900 粗、56–80px；其余是 10.5–12px 的灰小字。
- 想一样只属于这件事的画面淡淡垫在后面（航线和小飞机、很大很淡的「春」字、毛笔柱子、环、时间线），别都画成「标题 + 大数 + 小字」。
- 宣纸底墨字，朱砂只点最要紧的一处；节日可以整张铺朱砂、字用 #FFF6EC。不要渐变、阴影、卡片里再套圆角卡片。四边留 14–18px。

绑定
- 字里写 {{名字}}；要自己排的一组行、一组数在 script 里读 D["名字"]：数 → 数字；字 → 字符串；一组行 → {rows:[{title,note,accent,done,tap,value}],total}；一组数 → {values,labels,current}。取不到时是「—」/ null。比例用 value 算，note 只是给人看的字。
- 能点：元素上写 data-tap="动作"（行自带 tap 就照抄），至少 24px 高。名字里不写算式。

能绑的
${sources.joinToString("\n") { s -> "· ${s.label}（${s.id}）：${s.brief}\n" + s.fields.joinToString("\n") { f -> "  ${s.id}.${f.name}${if (f.args.isEmpty()) "" else " " + f.args} ${f.kind.label} · ${f.what}" } }}

能点的
${sources.flatMap { s -> s.actions.map { a -> "  ${s.id}.${a.name}${if (a.args.isEmpty()) "" else " " + a.args} · ${a.what}" } }.joinToString("\n")}

两张样板（编号是假的。照它们的路子给别的事想自己的画面，别照抄）
${listOf(CardSamples.ALL[0], CardSamples.ALL[3]).joinToString("\n\n") { "${it.what} · size ${it.size}\n${it.html}" }}
""".trimIndent()
}

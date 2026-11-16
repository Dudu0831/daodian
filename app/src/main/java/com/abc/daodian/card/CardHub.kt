package com.abc.daodian.card

import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.source.TimeSource
import com.abc.daodian.card.spec.CardSpecs

/**
 * 能绑什么、能点什么：各模块交的（`Module.cards`，按 `Modules.kt` 的顺序）+ 卡片模块自带的时间。
 * `DaodianApp.onCreate` 装进来 —— 桌面来要卡片时进程可能是刚拉起的，Application 先跑，拿得到。
 */
object CardHub {

    @Volatile
    private var installed: List<CardSource> = emptyList()

    private val builtIn: List<CardSource> = listOf(TimeSource())

    fun install(list: List<CardSource>) {
        val all = list + builtIn
        require(all.map { it.id }.distinct().size == all.size) { "卡片来源 id 重复：${all.map { it.id }}" }
        require(all.none { it.id == CardSpecs.OWN }) { "「${CardSpecs.OWN}」是卡片自己的数，模块不能用这个 id" }
        installed = list
    }

    val sources: List<CardSource> get() = installed + builtIn

    val vocabulary: CardSpecs.Vocabulary get() = CardSpecs.Vocabulary(sources)

    fun source(id: String): CardSource? = sources.firstOrNull { it.id == id }
}

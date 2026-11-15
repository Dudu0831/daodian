package com.abc.daodian.card

import com.abc.daodian.card.plugin.CardSource

/**
 * 各模块交出来的「能钉什么」（[CardSource]），按根目录 `Modules.kt` 的顺序。`DaodianApp.onCreate` 装进来 ——
 * 桌面来要卡片时进程可能是刚被拉起的，Application 先跑，拿得到。
 */
object CardHub {

    @Volatile
    var sources: List<CardSource> = emptyList()
        private set

    fun install(list: List<CardSource>) {
        require(list.map { it.id }.distinct().size == list.size) { "卡片来源 id 重复：${list.map { it.id }}" }
        sources = list
    }

    fun source(id: String): CardSource? = sources.firstOrNull { it.id == id }
}

package com.abc.daodian.intake

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/**
 * 分发：一条通知该给谁、怎么给。不碰 Android，单元测试测它（RouterTest）。
 *
 * [routes] 是路由表：订阅者 id → 它勾了的包名。表里有、代码里没注册的 id（改过名、删掉的模块）直接忽略。
 */
class Router(private val subscribers: List<NoticeSubscriber>) {

    init {
        require(subscribers.map { it.id }.distinct().size == subscribers.size) { "订阅者 id 重复：${subscribers.map { it.id }}" }
    }

    /** 勾了 [pkg] 的订阅者，按注册顺序。没人勾就是空的 —— 这条通知扔掉 */
    fun targets(pkg: String, routes: Map<String, Set<String>>): List<NoticeSubscriber> =
        subscribers.filter { pkg in routes[it.id].orEmpty() }

    /**
     * 交给每一个 [targets]，同时交、各交各的：一个抛错、一个慢，都不耽误别的。全部返回了才返回，
     * 返回收成了的那几个。取消照样往外抛
     */
    suspend fun deliver(
        targets: List<NoticeSubscriber>,
        give: suspend (NoticeSubscriber) -> Unit
    ): List<NoticeSubscriber> = supervisorScope {
        targets.map { s ->
            async {
                try {
                    give(s)
                    s
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }
}

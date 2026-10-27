package com.abc.daodian.intake.steward

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * 什么时候叫管家（DESIGN.md §12.4）：有人 [poke]（收件箱来了新的待办、打开 app），等 3 秒没人再叫才跑 ——
 * 这 3 秒里又来的一起办。一次只跑一轮：跑着的时候又叫的，跑完再防抖一次。
 * 进程在这 3 秒里被杀不要紧，待办都在库里，下次有人叫就接着办。
 *
 * @param work 跑一轮（管家 + 交接）。返回 true = 还有该接着办的，再叫自己一次（次数有上限，不会空转）
 */
@OptIn(FlowPreview::class)
class StewardScheduler(
    scope: CoroutineScope,
    private val work: suspend () -> Boolean,
    debounceMillis: Long = DEBOUNCE_MILLIS
) {

    private val pokes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            pokes.debounce(debounceMillis).collect {
                val more = try {
                    work()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (more) poke()
            }
        }
    }

    fun poke() {
        pokes.tryEmit(Unit)
    }

    companion object {
        /** 用户 10-26 定的 */
        const val DEBOUNCE_MILLIS = 3_000L
    }
}

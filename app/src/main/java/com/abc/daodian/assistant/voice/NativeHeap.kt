package com.abc.daodian.assistant.voice

/**
 * 让原生堆把已经释放的空页马上还给系统（`src/main/cpp/heap_purge.c`，bionic 的 `mallopt(M_PURGE…)`）。
 *
 * 语音模型放掉之后调一次：不然分配器攥着约 100MB 空页，荣耀几分钟后才收回去，别的手机不一定收，
 * 这段时间进程在后台顶着 300MB 容易被杀。见 VOICE_PLAN.md §2.3。
 */
internal object NativeHeap {

    private val loaded: Boolean = runCatching { System.loadLibrary("daodian_heap") }.isSuccess

    /** 还了没有。库没加载上就什么都不做，返回 false */
    fun purge(): Boolean = loaded && runCatching { purgeNative() }.getOrDefault(false)

    @JvmStatic
    private external fun purgeNative(): Boolean
}

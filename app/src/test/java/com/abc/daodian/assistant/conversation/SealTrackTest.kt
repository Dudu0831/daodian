package com.abc.daodian.assistant.conversation

import androidx.compose.runtime.derivedStateOf
import com.abc.daodian.runtime.model.Effort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class SealTrackTest {

    /**
     * 从设置页回来对话页重建：新的 track 从默认档起，印在 SideEffect 里写进存着的那档。
     * 写进去得能被看见，不然印一直画默认的浓淡（11-21 撞到的）
     */
    @Test
    fun chosenIsObservable() {
        val track = SealTrack(CoroutineScope(Dispatchers.Unconfined)) {}
        val shown = derivedStateOf { track.shown }
        assertEquals(Effort.DEFAULT, shown.value)
        track.chosen = Effort.MAX
        assertEquals(Effort.MAX, shown.value)
    }
}

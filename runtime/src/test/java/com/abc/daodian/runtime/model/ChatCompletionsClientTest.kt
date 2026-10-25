package com.abc.daodian.runtime.model

import com.abc.daodian.runtime.engine.Item
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsClientTest {

    private val now = ZonedDateTime.of(2026, 10, 23, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
    private val client = ChatCompletionsClient(ProviderProfile("https://example.test/v1", "key", "m", api = ProviderProfile.Api.CHAT))

    @Test
    fun `history lays out as chat messages with calls folded into the assistant message`() {
        val params = client.params(
            LlmRequest(
                system = "系统",
                input = listOf(
                    Item.UserMessage("明早提醒我", now),
                    Item.AssistantMessage("好，记两条"),
                    Item.ToolCall("c1", "create_reminder", "{\"a\":1}"),
                    Item.ToolCall("c2", "create_reminder", "{\"a\":2}"),
                    Item.ToolResult("c1", "建好了", ok = true),
                    Item.ToolResult("c2", "建好了", ok = true),
                    Item.AssistantMessage("都记下了"),
                    Item.UserMessage("谢谢", now)
                ),
                tools = emptyList(),
                background = "记得的事"
            )
        )
        val messages = params.messages()
        val roles = messages.map {
            when {
                it.isSystem() -> "system"
                it.isUser() -> "user"
                it.isAssistant() -> "assistant"
                it.isTool() -> "tool"
                else -> "?"
            }
        }
        assertEquals(listOf("system", "user", "user", "assistant", "tool", "tool", "assistant", "user"), roles)
        val folded = messages[3].asAssistant()
        assertEquals("好，记两条", folded.content().get().asText())
        assertEquals(listOf("c1", "c2"), folded.toolCalls().get().map { it.asFunction().id() })
        assertEquals(listOf("c1", "c2"), messages.subList(4, 6).map { it.asTool().toolCallId() })
        assertTrue("背景打了标记", messages[1].asUser().content().asText().startsWith(com.abc.daodian.runtime.context.Preamble.MARK))
    }

    @Test
    fun `thinking off sends no reasoning effort`() {
        val params = client.params(LlmRequest("系统", listOf(Item.UserMessage("你好", now)), emptyList()))
        assertFalse(params.reasoningEffort().isPresent)
    }
}

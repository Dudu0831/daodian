package com.abc.daodian.intake.data

import com.abc.daodian.intake.core.rule.AllOf
import com.abc.daodian.intake.core.rule.AppIn
import com.abc.daodian.intake.core.rule.BodyHasAny
import com.abc.daodian.intake.core.rule.BodyHasNone
import com.abc.daodian.intake.core.rule.BodyStartsWith
import com.abc.daodian.intake.core.rule.TitleIs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleCodecTest {

    private val every = AllOf(
        listOf(
            AppIn(setOf("com.android.mms", "com.cainiao.wireless")),
            TitleIs("老婆", exact = true),
            TitleIs("银行", exact = false),
            BodyStartsWith("到点"),
            BodyHasAny(listOf("取件码", "驿站")),
            BodyHasNone(listOf("验证码")),
            AllOf(listOf(AppIn(setOf("cmb.pb"))))
        )
    )

    @Test
    fun `every kind of condition comes back the same`() {
        assertEquals(every, RuleCodec.decode(RuleCodec.encode(every)))
    }

    @Test
    fun `the stored shape does not drift`() {
        val hard = AllOf(listOf(AppIn(setOf("com.android.mms")), BodyHasAny(listOf("取件码", "驿站"))))
        assertEquals(
            """{"type":"all","parts":[{"type":"app","pkgs":["com.android.mms"]},{"type":"body_any","words":["取件码","驿站"]}]}""",
            RuleCodec.encode(hard)
        )
    }

    @Test
    fun `anything it cannot read is null as a whole, never a looser rule`() {
        assertNull(RuleCodec.decode("不是 JSON"))
        assertNull(RuleCodec.decode("""{"type":"app","pkgs":["x"]}"""))
        assertNull(RuleCodec.decode("""{"type":"all","parts":[{"type":"app","pkgs":["x"]},{"type":"sender_is","name":"老婆"}]}"""))
        assertNull(RuleCodec.decode("""{"type":"all","parts":[{"type":"title","text":"老婆"}]}"""))
        assertNull(RuleCodec.decode("""{"type":"all","parts":[{"type":"body_any","words":["驿站",3]}]}"""))
    }
}

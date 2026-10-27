package com.abc.daodian.intake.core

import com.abc.daodian.intake.core.format.Formats
import com.abc.daodian.intake.core.rule.AllOf
import com.abc.daodian.intake.core.rule.AppIn
import com.abc.daodian.intake.core.rule.BodyHasAny
import com.abc.daodian.intake.core.rule.BodyHasNone
import com.abc.daodian.intake.core.rule.BodyStartsWith
import com.abc.daodian.intake.core.rule.RuleCheck
import com.abc.daodian.intake.core.rule.RuleDraft
import com.abc.daodian.intake.core.rule.TitleIs
import com.abc.daodian.intake.core.rule.Verdict
import com.abc.daodian.intake.core.rule.Verdict.NO
import com.abc.daodian.intake.core.rule.Verdict.UNKNOWN
import com.abc.daodian.intake.core.rule.Verdict.YES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 硬条件、规则、建规则的闸 */
class RuleTest {

    private val parcel = RuleDraft(
        name = "快递到了提醒取",
        apps = listOf(SMS, CAINIAO),
        bodyAny = listOf("取件码", "驿站"),
        bodyNone = listOf("验证码"),
        soft = "快递到了驿站或快递柜，当天 18:00 提醒我去取，标题带上取件码"
    )

    private val wife = RuleDraft(name = "老婆派的事", apps = listOf(WECHAT), title = "老婆", startsWith = "到点", soft = "她让我做的事建成提醒")

    private fun test(draft: RuleDraft, pkg: String, title: String?, text: String?, redacted: Boolean = false): Verdict =
        draft.hard().test(Formats.of(pkg).normalize(notice(pkg, title, text, redacted = redacted)))

    @Test
    fun `words are or, conditions are and`() {
        assertEquals(YES, test(parcel, SMS, "菜鸟驿站", "您的包裹已到驿站"))
        assertEquals(YES, test(parcel, CAINIAO, null, "取件码 3-2-1088"))
        assertEquals(NO, test(parcel, SMS, "10086", "您的话费余额"))
        assertEquals(NO, test(parcel, SMS, null, "取件码 3-2-1088，验证码 1234"))
        assertEquals(NO, test(parcel, WECHAT, null, "取件码 3-2-1088"))
    }

    @Test
    fun `title and code are checked on the cleaned up chat`() {
        assertEquals(YES, test(wife, WECHAT, "老婆(2条)", "[2条]老婆: 到点 晚上带瓶酱油"))
        assertEquals(YES, test(wife, WECHAT, "老婆", "到点买菜"))
        assertEquals(NO, test(wife, WECHAT, "老婆", "晚上吃啥"))
        assertEquals(NO, test(wife, WECHAT, "老妈", "到点 买菜"))
        assertEquals(YES, test(wife.copy(startsWith = "DD"), WECHAT, "老婆", "dd 买菜"))
    }

    @Test
    fun `title can be contains instead of equals`() {
        val bank = RuleDraft(name = "x", apps = listOf(SMS), title = "银行", titleExact = false, soft = "x")
        assertEquals(YES, test(bank, SMS, "招商银行", "支出 58.00"))
        assertEquals(NO, test(bank.copy(titleExact = true), SMS, "招商银行", "支出 58.00"))
    }

    @Test
    fun `a redacted body is unknown, but app and title still decide`() {
        assertEquals(UNKNOWN, test(parcel, SMS, "菜鸟驿站", "敏感数据已隐藏", redacted = true))
        assertEquals(NO, test(parcel, WECHAT, "菜鸟驿站", "敏感数据已隐藏", redacted = true))
        assertEquals(NO, test(wife, WECHAT, "老妈", "敏感数据已隐藏", redacted = true))
        val bank = RuleDraft(name = "招行记账", apps = listOf(CMB), soft = "都交给记账")
        assertEquals(YES, test(bank, CMB, "招商银行", "敏感数据已隐藏", redacted = true))
    }

    @Test
    fun `and short circuits on no and lets no beat unknown`() {
        assertEquals(NO, YES and NO)
        assertEquals(NO, UNKNOWN and NO)
        assertEquals(UNKNOWN, YES and UNKNOWN)
        assertEquals(YES, YES and YES)
    }

    @Test
    fun `draft builds conditions in a fixed order, trimmed and deduplicated`() {
        val hard = RuleDraft(
            name = "x", apps = listOf(" $SMS ", SMS, ""), title = " 老婆 ", startsWith = " ", bodyAny = listOf("驿站", " 驿站", ""),
            soft = "x"
        ).hard()
        assertEquals(AllOf(listOf(AppIn(setOf(SMS)), TitleIs("老婆", true), BodyHasAny(listOf("驿站")))), hard)
        assertEquals(listOf(AppIn::class, TitleIs::class, BodyStartsWith::class, BodyHasAny::class, BodyHasNone::class),
            wife.copy(bodyAny = listOf("买"), bodyNone = listOf("不")).hard().parts.map { it::class })
    }

    @Test
    fun `describe reads like the trace`() {
        assertEquals("短信、菜鸟（含 取件码 / 驿站，不含 验证码）", parcel.hard().describe(NAMES))
        assertEquals("微信（标题是「老婆」，开头是「到点」）", wife.hard().describe(NAMES))
        assertEquals("招商银行", RuleDraft(name = "x", apps = listOf(CMB), soft = "x").hard().describe(NAMES))
    }

    @Test
    fun `a rule watches only its apps`() {
        val r = rule(1, parcel)
        assertTrue(r.watches(SMS))
        assertFalse(r.watches(WECHAT))
    }

    @Test
    fun `the gate lets a clear rule through`() {
        assertTrue(RuleCheck.problems(parcel, setOf(SMS, CAINIAO), active = 3).isEmpty())
        assertTrue(RuleCheck.problems(wife, setOf(WECHAT), active = RuleCheck.MAX_RULES - 1).isEmpty())
    }

    @Test
    fun `the gate says what is wrong`() {
        fun problems(d: RuleDraft, active: Int = 0) = RuleCheck.problems(d, setOf(SMS, WECHAT), active).joinToString("\n")
        assertTrue(problems(parcel).contains(CAINIAO))
        assertTrue(problems(parcel.copy(apps = emptyList())).contains("至少要一个包名"))
        assertTrue(problems(parcel.copy(apps = listOf(SMS), soft = "  ")).contains("soft 是空的"))
        assertTrue(problems(parcel.copy(apps = listOf(SMS), bodyAny = listOf("码", "驿站"))).contains("「码」"))
        assertTrue(problems(wife.copy(title = " ")).contains("title 是空的"))
        assertTrue(problems(wife.copy(startsWith = "")).contains("starts_with 是空的"))
        assertTrue(problems(wife.copy(name = "")).contains("name 是空的"))
        assertTrue(problems(wife, active = RuleCheck.MAX_RULES).contains("规则满了"))
    }
}

package com.abc.daodian.intake.core.rule

import com.abc.daodian.intake.core.format.Normalized
import com.abc.daodian.intake.core.rule.Verdict.NO
import com.abc.daodian.intake.core.rule.Verdict.UNKNOWN
import com.abc.daodian.intake.core.rule.Verdict.YES

/** 一个硬条件判一条通知。遮蔽版的正文判不了，是 [UNKNOWN]：先存、等重读（DESIGN.md §12.3） */
enum class Verdict {
    YES, NO, UNKNOWN;

    /** 且：有 NO 就 NO，有 UNKNOWN 就 UNKNOWN */
    infix fun and(other: Verdict): Verdict = when {
        this == NO || other == NO -> NO
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        else -> YES
    }
}

/** 包名 → 人话名字（`AppCatalog.label`）。核心不碰 Android，由外面给 */
fun interface AppNames {
    fun of(pkg: String): String
}

/**
 * 硬条件（Specification）：只管这条进不进管家，不管交给谁（DESIGN.md §12.1）。比的是整理过格式的标题、正文。
 * 加一种 = 加一个类，判法和人话都写在类里；存库的编解码（`RuleCodec`）跟着加一支
 */
sealed interface Condition {

    fun test(n: Normalized): Verdict

    /** 痕上、规则页上的人话：「含 取件码 / 驿站」 */
    fun describe(names: AppNames): String
}

/** 哪几个 app 的。每条规则至少有一个 */
data class AppIn(val pkgs: Set<String>) : Condition {
    override fun test(n: Normalized) = if (n.raw.pkg in pkgs) YES else NO
    override fun describe(names: AppNames) = pkgs.joinToString("、") { names.of(it) }
}

/** 标题等于（[exact]）或包含 [text]，不分大小写。遮蔽只遮正文，标题照样判得了 */
data class TitleIs(val text: String, val exact: Boolean) : Condition {
    override fun test(n: Normalized): Verdict {
        val title = n.title ?: return NO
        val hit = if (exact) title.equals(text, ignoreCase = true) else title.contains(text, ignoreCase = true)
        return if (hit) YES else NO
    }

    override fun describe(names: AppNames) = if (exact) "标题是「$text」" else "标题含「$text」"
}

/** 正文（连展开后的那段）含这几个词之一 */
data class BodyHasAny(val words: List<String>) : Condition {
    override fun test(n: Normalized): Verdict {
        if (n.raw.redacted) return UNKNOWN
        val text = n.fullText ?: return NO
        return if (words.any { text.contains(it, ignoreCase = true) }) YES else NO
    }

    override fun describe(names: AppNames) = "含 " + words.joinToString(" / ")
}

/** 正文（连展开后的那段）一个都不含 */
data class BodyHasNone(val words: List<String>) : Condition {
    override fun test(n: Normalized): Verdict {
        if (n.raw.redacted) return UNKNOWN
        val text = n.fullText ?: return YES
        return if (words.none { text.contains(it, ignoreCase = true) }) YES else NO
    }

    override fun describe(names: AppNames) = "不含 " + words.joinToString(" / ")
}

/** 正文以暗号开头，不分大小写。设了暗号，开头不对就是没中、不存（DESIGN.md §12.1） */
data class BodyStartsWith(val code: String) : Condition {
    override fun test(n: Normalized): Verdict {
        if (n.raw.redacted) return UNKNOWN
        return if (n.body?.startsWith(code, ignoreCase = true) == true) YES else NO
    }

    override fun describe(names: AppNames) = "开头是「$code」"
}

/** 且（Composite）。遇到 NO 就不往下判了 */
data class AllOf(val parts: List<Condition>) : Condition {
    override fun test(n: Normalized): Verdict {
        var v = YES
        for (c in parts) {
            v = v and c.test(n)
            if (v == NO) break
        }
        return v
    }

    /** 「短信、菜鸟（含 取件码 / 驿站，不含 验证码）」：app 在前，别的放括号里 */
    override fun describe(names: AppNames): String {
        val (apps, rest) = parts.partition { it is AppIn }
        val head = apps.joinToString("、") { it.describe(names) }
        return if (rest.isEmpty()) head else "$head（${rest.joinToString("，") { it.describe(names) }}）"
    }
}

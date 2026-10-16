package com.abc.daodian

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 依赖规矩（DESIGN.md §2.2）里能机器查的：扫每个源文件的 import，和下面那张表对照。
 * `runtime` 是单独的 Gradle 模块（:runtime），它碰不着 Android 和 app 由编译器管，这里不查；app 里谁能用它见下表。
 *
 * | 哪里 | 能 import app 里的哪些 |
 * |---|---|
 * | `assistant` | 自己、`runtime`、`shared` |
 * | `shell` | 自己、`assistant`、`runtime`、`shared`、`update` |
 * | `module` | 两个插口包 `assistant/skill`、`shell/entry` |
 * | 模块的核心层（提醒的 domain/data/application/scheduling/delivery，记账的 domain/data/capture） | 自己、`shared`、通知监听层的核心 |
 * | 模块的其余部分 | 再加上 `runtime`、两个插口包、`module`。别的模块不碰 |
 * | 通知监听层的核心 | 自己、`shared` |
 * | 通知监听层的接头和 `presentation/` | 再加上 `shell/entry`、`module` |
 * | `update`、`shared` | 自己、`shared` |
 * | `agent`（只剩旧全类名的空壳） | `assistant` |
 *
 * 根目录（装配）随便用。`BuildConfig`、`R` 谁都能用。
 */
class ArchitectureTest {

    /** 单元测试的工作目录是 app/ */
    private val src = File("src/main/java/com/abc/daodian")

    private class Rule(val allowed: List<String>, val forbidden: List<String> = emptyList()) {
        fun allows(name: String): Boolean = allowed.any { name.isUnder(it) } && forbidden.none { name.isUnder(it) }
    }

    private fun ruleFor(path: String): Rule? {
        val p = path.replace(File.separatorChar, '/')
        return when {
            !p.contains('/') -> null // 根目录：装配
            p.startsWith("assistant/") -> Rule(listOf("assistant", "runtime", "shared") + ALWAYS)
            p.startsWith("shell/") -> Rule(listOf("shell", "assistant", "runtime", "shared", "update") + ALWAYS)
            p.startsWith("module/") -> Rule(listOf("module") + PLUGS + ALWAYS)
            p.startsWith("agent/") -> Rule(listOf("assistant") + ALWAYS)
            p.startsWith("update/") -> Rule(listOf("update", "shared") + ALWAYS)
            p.startsWith("shared/") -> Rule(listOf("shared") + ALWAYS)
            p.startsWith("intake/") -> {
                val edge = p == "intake/IntakeModule.kt" || p.startsWith("intake/presentation/")
                if (edge) Rule(listOf("intake", "shared", "module", "shell.entry") + ALWAYS)
                else Rule(listOf("intake", "shared") + ALWAYS, INTAKE_EDGE)
            }
            p.startsWith("reminder/") || p.startsWith("ledger/") -> {
                val module = p.substringBefore('/')
                val layer = p.substringAfter('/').substringBefore('/')
                if (layer in CORE.getValue(module)) Rule(listOf(module, "shared") + INTAKE_CORE + ALWAYS)
                else Rule(listOf(module, "shared", "runtime", "module") + PLUGS + INTAKE_CORE + ALWAYS)
            }
            else -> Rule(ALWAYS)
        }
    }

    @Test
    fun `every package only imports what DESIGN 2_2 allows`() {
        assertTrue("找不到 ${src.absolutePath}", src.isDirectory)
        val bad = src.walk().filter { it.extension == "kt" }.flatMap { file ->
            val rel = file.relativeTo(src).path
            val rule = ruleFor(rel) ?: return@flatMap emptySequence()
            file.readLines().asSequence().withIndex().mapNotNull { (i, line) ->
                val name = IMPORT.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
                if (rule.allows(name)) null else "$rel:${i + 1}  import com.abc.daodian.$name"
            }
        }.toList()
        assertTrue("越界的 import：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    companion object {
        private val IMPORT = Regex("""^import com\.abc\.daodian\.(\S+)""")

        /** 谁都能用的 */
        private val ALWAYS = listOf("BuildConfig", "R")

        /** 模块能碰的两个插口包 */
        private val PLUGS = listOf("assistant.skill", "shell.entry")

        /** 通知监听层的核心里，模块能碰的那几样（DESIGN.md §2.2 第 8 条） */
        private val INTAKE_CORE = listOf("intake.Notice", "intake.NoticeSubscriber", "intake.Intake", "intake.IntakeRoutes")

        /** 通知监听层碰模块接头和壳的那两处，核心不碰 */
        private val INTAKE_EDGE = listOf("intake.IntakeModule", "intake.presentation")

        /** 模块的核心层：不碰 runtime、助手、壳 */
        private val CORE = mapOf(
            "reminder" to setOf("domain", "data", "application", "scheduling", "delivery"),
            "ledger" to setOf("domain", "data", "capture")
        )

        /** `assistant.skill.AssistantSkill` 在 `assistant.skill` 底下；`intake.IntakeModule` 不在 `intake.Intake` 底下 */
        private fun String.isUnder(prefix: String): Boolean = this == prefix || startsWith("$prefix.")
    }
}

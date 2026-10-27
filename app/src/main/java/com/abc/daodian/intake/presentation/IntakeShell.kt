package com.abc.daodian.intake.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.abc.daodian.intake.Intake
import com.abc.daodian.intake.IntakeModule
import com.abc.daodian.intake.IntakeRoutes
import com.abc.daodian.intake.data.IntakeDatabase
import com.abc.daodian.intake.data.IntakeRepos
import com.abc.daodian.shared.data.rows
import com.abc.daodian.shared.format.DiagnosticText.time
import com.abc.daodian.shell.entry.AppNav
import com.abc.daodian.shell.entry.PermissionStatus
import com.abc.daodian.shell.entry.ShellEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 通知监听层给壳的：规则页和收到的通知、「权限与监听」页里的几组、设置首页那一行上的一句、诊断那一段 */
object IntakeShell : ShellEntry {

    override val id = IntakeModule.ID

    override val label = IntakeModule.LABEL

    /**
     * 「导出诊断」里这一段：使用权、监听连没连着；规则几条、各听哪些包名；近 7 天进来几条、交给谁、不相关、没办成各几条。
     * 不写规则名、软规则、标题、正文（§11）
     */
    override suspend fun diagnostics(context: Context): String = withContext(Dispatchers.IO) {
        buildString {
            val l = Intake.listener.value
            appendLine("使用权 ${if (Intake.granted(context)) "开着" else "没开"} · 监听${if (l.connected) "连着，从 ${time(l.since)} 起" else "没连着"}")
            val rules = IntakeRepos.rules(context).all().first()
            appendLine("规则 ${rules.count { it.enabled }} 条在听，${rules.count { !it.enabled }} 条停着")
            rules.forEach { appendLine("  r${it.id}${if (it.enabled) "" else "（停）"} 听：" + it.apps.sorted().joinToString("、")) }
            val db = IntakeDatabase.get(context).openHelper.readableDatabase
            val since = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            val states = db.rows("SELECT state, COUNT(*) FROM notices WHERE receivedAt >= $since GROUP BY state")
            appendLine("近 7 天进来：" + states.joinToString("、") { (st, n) -> "$st $n" }.ifEmpty { "没有" })
            val ignored = db.rows(
                "SELECT COUNT(*) FROM notice_rules WHERE outcome = 'IGNORED' AND noticeId IN (SELECT id FROM notices WHERE receivedAt >= $since)"
            ).firstOrNull()?.firstOrNull() ?: "0"
            appendLine("  不相关 $ignored 条（按规则数）")
            db.rows("SELECT target, state, COUNT(*) FROM handoffs WHERE createdAt >= $since GROUP BY target, state")
                .forEach { (target, st, n) -> appendLine("  交给 $target · $st $n") }
        }
    }

    override fun NavGraphBuilder.routes(nav: AppNav) {
        composable(IntakeRoutes.RULES) { RulesScreen(onBack = nav::back, open = nav::open) }
        composable(IntakeRoutes.RULE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            RuleScreen(id = it.arguments?.getLong("id") ?: 0L, onBack = nav::back, open = nav::open)
        }
        composable(IntakeRoutes.NOTICES) { NoticesScreen(onBack = nav::back, open = nav::open) }
        composable(IntakeRoutes.NOTICE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            NoticeScreen(id = it.arguments?.getLong("id") ?: 0L, onBack = nav::back, open = nav::open)
        }
    }

    @Composable
    override fun PermissionSection(open: (String) -> Unit) = IntakePermissionSection(open)

    @Composable
    override fun permissionStatus(): PermissionStatus? = intakePermissionStatus()?.let { (text, ok) -> PermissionStatus(text, ok) }
}

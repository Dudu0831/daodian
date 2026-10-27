package com.abc.daodian.intake.assistant

import android.content.Context
import com.abc.daodian.intake.Intake
import com.abc.daodian.intake.Notice
import com.abc.daodian.intake.NoticeRouting
import com.abc.daodian.intake.RecentTitles
import com.abc.daodian.intake.core.format.Formats
import com.abc.daodian.intake.core.ports.RuleRepository
import com.abc.daodian.intake.core.rule.AppNames
import com.abc.daodian.intake.data.IntakeDatabase
import com.abc.daodian.intake.data.IntakeRepos
import com.abc.daodian.shared.apps.AppCatalog

/** 建规则的工具要的东西都从这里拿（工具不碰 Android，测试里换成假的） */
interface RuleDesk {

    val rules: RuleRepository

    val names: AppNames

    /** 桌面上有图标的 app：名字、包名 */
    suspend fun apps(): List<AppCatalog.App>

    /** 这个 app 最近发过通知的标题（整理过格式的），新的在前 */
    suspend fun recentTitles(pkg: String): List<String>

    /** 拿来预演的通知：通知栏里现在挂着的 + 收件箱里最近的 */
    suspend fun samples(): List<Notice>

    /** 每条规则进来过几条 */
    suspend fun counts(): Map<Long, Int>

    /** 建好规则后把通知栏扫一遍：已经挂在那里的、对得上的当场进收件箱（以前勾上 app 当场扫，同一个意思） */
    suspend fun sweep()
}

/** 真机上的那份 */
class IntakeDesk(context: Context) : RuleDesk {

    private val app = context.applicationContext

    override val rules: RuleRepository = IntakeRepos.rules(app)

    override val names = AppNames { AppCatalog.label(app, it) }

    override suspend fun apps(): List<AppCatalog.App> = AppCatalog.launchable(app)

    override suspend fun recentTitles(pkg: String): List<String> {
        val live = Intake.active().orEmpty().filter { it.pkg == pkg }.mapNotNull { Formats.of(pkg).normalize(it).title }
        return (RecentTitles.of(pkg) + live).distinct().take(TITLES)
    }

    override suspend fun samples(): List<Notice> {
        val since = System.currentTimeMillis() - SAMPLE_DAYS * 24 * 60 * 60 * 1000L
        val inbox = IntakeDatabase.get(app).dao().recentNotices(since, SAMPLE_LIMIT).map {
            Notice(it.pkg, it.notifKey, it.postTime, it.at, it.title, it.text, it.extra, it.extras, it.redacted, it.how)
        }
        return Intake.active().orEmpty() + inbox
    }

    override suspend fun counts(): Map<Long, Int> = IntakeDatabase.get(app).dao().ruleCounts().associate { it.ruleId to it.n }

    /** 只在新链路开着时扫：老链路上扫一遍是交给老的订阅者，和规则无关 */
    override suspend fun sweep() {
        if (NoticeRouting.hub(app)) Intake.sweep(HOW_RULE)
    }

    private companion object {
        const val TITLES = 12
        const val SAMPLE_DAYS = 7L
        const val SAMPLE_LIMIT = 300

        /** 建好规则那一刻扫到的，写进 Notice.how */
        const val HOW_RULE = "rule"
    }
}

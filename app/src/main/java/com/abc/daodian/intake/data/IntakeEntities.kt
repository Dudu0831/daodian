package com.abc.daodian.intake.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.abc.daodian.intake.core.inbox.HandoffState
import com.abc.daodian.intake.core.inbox.InboxState

/*
 * intake.db 的表（DESIGN.md §12）。规则只在对话里建；收件箱只存中了规则的通知，办完的留 30 天；
 * 交接是 Outbox：管家定了交给谁，和收件箱的状态一个事务写下，另有人去交。
 * 表名、列名、枚举的名字都存在手机上，不改名。以后改表：version + 1，IntakeDatabase 里加一条 AutoMigration
 */

/** 一条规则。停了不删（[enabled] 是 0），设置页能撤销 */
@Entity(tableName = "rules")
data class RuleRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 硬条件，JSON（[RuleCodec]） */
    val hard: String,
    /** 软规则，管家读 */
    val soft: String,
    val enabled: Boolean,
    val createdAt: Long,
    /** 最近一次停的时刻；启用着是 null */
    val stoppedAt: Long? = null
)

/** 收件箱的一条：原样的字段 + 整理过的标题正文 + 状态 */
@Entity(
    tableName = "notices",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["pkg", "notifKey", "postTime"]),
        Index(value = ["state", "receivedAt"]),
        Index(value = ["state", "doneAt"])
    ]
)
data class NoticeRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val notifKey: String,
    val postTime: Long,
    /** app 自己写的时刻（`Notice.at`） */
    val at: Long,
    val title: String?,
    val text: String?,
    val extra: String?,
    /** 整个 extras 的 JSON，原样 */
    val extras: String,
    val redacted: Boolean,
    /** 怎么抓到的：posted / active / unlock / retry+5s …… */
    val how: String,
    /** `key | postTime | 正文`，同一条同一段正文只存一次 */
    val fingerprint: String,
    /** 整理过格式的，比硬条件、给管家看的是它们 */
    val normTitle: String?,
    val normBody: String?,
    val state: InboxState,
    /** 管家办过几次还没覆盖全 */
    val attempts: Int = 0,
    /** 第一次存下的时刻：遮蔽版换成真正文也不变 */
    val receivedAt: Long,
    /** 办完的时刻，清 30 天前的看它 */
    val doneAt: Long? = null
)

/** 一条规则对这条通知的说法 */
enum class RuleOutcome { HANDED, IGNORED }

/** 一条通知中了哪几条规则，每条后来怎么定的。[outcome] 是 null = 还没说法 */
@Entity(tableName = "notice_rules", primaryKeys = ["noticeId", "ruleId"], indices = [Index("ruleId")])
data class NoticeRuleRow(
    val noticeId: Long,
    val ruleId: Long,
    val outcome: RuleOutcome? = null,
    /** 交给了哪几个去处，逗号隔开：「ledger,reminder」 */
    val targets: String? = null,
    /** 不相关的理由 */
    val why: String? = null,
    val decidedAt: Long? = null
)

/**
 * 交接（Outbox）：管家定了交给 [target]，一行一次。同一条后来又中了新规则、又交给同一个去处，是新的一行。
 * 回执（[receipt]、[route]）由模块写回：当场的「待整理」，后来补的「午饭 58.00」
 */
@Entity(tableName = "handoffs", indices = [Index("noticeId"), Index(value = ["state", "createdAt"])])
data class HandoffRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noticeId: Long,
    /** 模块的 `NoticeTarget.id`：「ledger」「reminder」 */
    val target: String,
    /** 管家写给子代理的附言 */
    val note: String,
    val state: HandoffState,
    /** 交了几次都抛错 */
    val attempts: Int = 0,
    val createdAt: Long,
    /** 最后一次交不出去的错 */
    val error: String? = null,
    val receipt: String? = null,
    /** 点回执跳去哪（提醒的编辑页） */
    val route: String? = null,
    val reportedAt: Long? = null
)

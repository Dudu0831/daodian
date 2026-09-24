package com.abc.daodian.ledger.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.abc.daodian.ledger.domain.AccountType
import com.abc.daodian.ledger.domain.Actor
import com.abc.daodian.ledger.domain.CategoryKind
import com.abc.daodian.ledger.domain.Direction
import com.abc.daodian.ledger.domain.TimeBasis
import com.abc.daodian.ledger.domain.TxnSource
import com.abc.daodian.ledger.domain.TxnState

/*
 * ledger.db 的表。设计和理由见 LEDGER_PLAN.md §10 —— 以后所有统计都从这里取，改表前先读那一节。
 *
 * 规矩：
 *  - 原始通知是唯一真相，永不删；流水是派生的，作废不删
 *  - 金额一律整数分、正数，正负看方向
 *  - 以后改表：version + 1，在 LedgerDatabase.autoMigrations 里加一条（纯加列用 AutoMigration）
 */

enum class RawState { PENDING, DONE, IGNORED, UNREADABLE, SUPERSEDED }

@Entity(
    tableName = "raw_notification",
    indices = [Index(value = ["fingerprint"], unique = true), Index(value = ["state", "postTime"])]
)
data class RawNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val notifKey: String,
    val postTime: Long,
    val title: String?,
    val text: String?,
    /** extras 里能读的正文（bigText、subText 之类），和 text 重复的已去掉 */
    val extra: String?,
    /** 整个 extras 的 JSON，原样 */
    val extras: String,
    val capturedAt: Long,
    /**
     * 怎么抓到的：posted（实时回调）/ active（监听连上时扫）/ unlock（解锁时扫）/ organize（整理前扫，
     * 09-24 以前叫 manual）/ tap（抓取页上手动抓）/ retry+Ns（遮蔽后重读）/ import
     */
    val capturedHow: String,
    /** `key | postTime | text`，同一条通知同一段正文只存一次 */
    val fingerprint: String,
    val redacted: Boolean,
    val state: RawState = RawState.PENDING,
    /** 忽略 / 看不清的理由（模型写的） */
    val stateNote: String? = null,
    val processedAt: Long? = null
)

@Entity(tableName = "txn", indices = [Index("day"), Index("state"), Index("merchantId")])
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val direction: Direction,
    /** 分，> 0 */
    val amount: Long,
    val occurredAt: Long,
    val timeBasis: TimeBasis,
    /** 本地日期 yyyyMMdd，写入时按当时时区算好；统计只按它分段 */
    val day: Int,
    val accountId: Long?,
    val channel: String?,
    val merchantRaw: String?,
    val merchantId: Long?,
    val summary: String,
    val note: String?,
    val source: TxnSource,
    val state: TxnState,
    val confidence: Double?,
    val ask: String?,
    val parsedBy: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** 分摊：统计从这里加。不拆的流水就一行；categoryId 为 null = 未归类 */
@Entity(tableName = "allocation", indices = [Index("txnId"), Index("categoryId")])
data class Allocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val txnId: Long,
    val categoryId: Long?,
    val amount: Long
)

/** 流水 ↔ 原始通知。一条通知最多属于一笔（主键就是 rawId） */
@Entity(tableName = "txn_raw", primaryKeys = ["rawId"], indices = [Index("txnId")])
data class TxnRaw(val rawId: Long, val txnId: Long)

/** 流水 ↔ 流水。先只有退款（from = 退款那笔，to = 原笔） */
@Entity(tableName = "txn_link", primaryKeys = ["fromId", "toId", "kind"], indices = [Index("toId")])
data class TxnLink(val fromId: Long, val toId: Long, val kind: String)

@Entity(tableName = "category", indices = [Index(value = ["parentId", "name"])])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** null = 一级 */
    val parentId: Long?,
    val kind: CategoryKind,
    val sort: Int,
    val archived: Boolean = false,
    val createdBy: String
)

@Entity(tableName = "merchant", indices = [Index(value = ["name"], unique = true)])
data class Merchant(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 商户记忆：以后这家默认归哪类。**只有用户确认过才写** */
    val categoryId: Long? = null,
    val confirmedAt: Long? = null,
    val createdAt: Long
)

/** 原样商户串 → 商户。整理时先按它把记忆查出来喂给模型 */
@Entity(tableName = "merchant_alias", primaryKeys = ["alias"], indices = [Index("merchantId")])
data class MerchantAlias(val alias: String, val merchantId: Long)

@Entity(tableName = "account", indices = [Index(value = ["bank", "tail", "type"])])
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bank: String,
    val tail: String?,
    val type: AccountType,
    val label: String? = null
)

@Entity(tableName = "tag", indices = [Index(value = ["name"], unique = true)])
data class Tag(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val createdAt: Long)

@Entity(tableName = "txn_tag", primaryKeys = ["txnId", "tagId"], indices = [Index("tagId")])
data class TxnTag(val txnId: Long, val tagId: Long)

/** 改动历史：新建不记（createdAt + parsedBy 够了），之后每次改都记 */
@Entity(tableName = "change_log", indices = [Index("txnId")])
data class ChangeLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val txnId: Long,
    val field: String,
    val old: String?,
    val new: String?,
    val actor: Actor,
    val reason: String?,
    val at: Long
)

/** 后台 agent 每跑一次记一行：什么时候、看了几条、记了几笔、出了什么错。设置页和印章纸签上看 */
@Entity(tableName = "agent_run", indices = [Index("startedAt")])
data class AgentRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** organize / check … */
    val kind: String,
    /** 谁叫它跑的：periodic / manual / check */
    val reason: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val rawCount: Int = 0,
    val recorded: Int = 0,
    val ignored: Int = 0,
    val pending: Int = 0,
    val error: String? = null,
    val model: String? = null
)

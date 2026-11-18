package com.abc.daodian.reminder.application

import android.content.Context
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.ReminderItem
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.data.Ring
import com.abc.daodian.reminder.data.Phase
import com.abc.daodian.reminder.data.remindBeforeColumn
import com.abc.daodian.reminder.data.timesColumn
import com.abc.daodian.reminder.delivery.Fired
import com.abc.daodian.reminder.delivery.Notifier
import com.abc.daodian.reminder.domain.Checklist
import com.abc.daodian.reminder.domain.ChecklistEdit
import com.abc.daodian.reminder.domain.ReminderSpec
import com.abc.daodian.reminder.scheduling.CheckTime
import com.abc.daodian.reminder.scheduling.FireHandler
import com.abc.daodian.reminder.scheduling.Rescheduler
import com.abc.daodian.reminder.scheduling.Schedule
import com.abc.daodian.reminder.widget.WidgetUpdater
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 提醒的所有写操作，app 里只有这一个入口：模型建 / 改 / 停 / 删的（`create_reminder`、`update_reminder`、`stop_reminders`、`delete_reminders`）、
 * 手动建 / 改、完成、稍后、删、撤销、重排。通知按钮、全屏页、小组件的 ✓ 也走这里；响了之后排下一下在 `FireHandler`。
 *
 * 「这一次排到哪了」全由 [Schedule] 算（DESIGN.md §4.6），这里只管落库和排闹钟。改完一律喊小组件重画一次（[WidgetUpdater]）——
 * 它在别的进程里，收不到 Room 的 Flow。
 */
object Reminders {

    /** 「稍后」挪多久 */
    const val SNOOZE_MS = 10 * 60 * 1000L

    /**
     * 模型建出来的一条提醒「落库 → 排闹钟」，返回新提醒的 id。对话页和桌面速记都经由 `create_reminder` 到这里。
     * 桌面上那一行先亮成朱砂的「刚记下」（[WidgetUpdater.announce]），速记记完回到桌面就看得见。
     */
    suspend fun commitPlan(context: Context, rawInput: String, spec: ReminderSpec, parsedBy: String, items: List<String> = emptyList()): Long {
        val saved = save(context, null, spec, rawInput, parsedBy)!!
        addItems(context, saved.id, items.map { it to false })
        WidgetUpdater.announce(context.applicationContext, saved.id)
        return saved.id
    }

    /**
     * 模型改了一条（`update_reminder`）：整条换成 [spec]（已经过了闸门），原话、建的时刻不动，重新排期。没有这一条是 null。
     * 响过了、完成了的改完一律回到 SCHEDULED；稍后作废；还挂在通知栏上的那条一起收掉 —— 不然点它的「完成」会把改好的这条也完成了
     */
    suspend fun updatePlan(context: Context, id: Long, spec: ReminderSpec, parsedBy: String): Reminder? =
        save(context, id, spec, rawInput = null, parsedBy = parsedBy)?.also {
            WidgetUpdater.refresh(context.applicationContext)
        }

    /** 手动建 / 改一条提醒 —— 逃生舱，必须能完全脱离 AI 用。见 DESIGN.md §2.1 */
    suspend fun upsertManual(context: Context, id: Long?, spec: ReminderSpec, newItems: List<Pair<String, Boolean>> = emptyList()) {
        val saved = save(context, id, spec, rawInput = spec.title, parsedBy = null)
        // 新建还没记下时打的清单跟着「记下」一起存；改已有的，清单早就当场存了
        if (id == null && saved != null) addItems(context, saved.id, newItems)
        WidgetUpdater.refresh(context.applicationContext)
    }

    /**
     * 建（[id] = null）或整条改：几件事换成 [spec]，「这一次」从 [ReminderSpec.day] 头一个钟点重新起，排第一下（[Schedule.start]）。
     * 排不出来的（一次性的、时刻全在过去）也照样排在它的正点上 —— 已经过了，巡检当场补发，不会悄悄丢掉。
     * 改的时候原话不动，[parsedBy] 是 null 就留着原来的。
     *
     * 整段 NonCancellable：插完库、闹钟还没排上的那一瞬间如果被取消（页面关了、桌面速记被 Home 掉），
     * 就会留下一条 SCHEDULED 却没有闹钟的记录，要等下一个重排触发源才补得上。
     */
    private suspend fun save(context: Context, id: Long?, spec: ReminderSpec, rawInput: String?, parsedBy: String?): Reminder? =
        withContext(NonCancellable) {
            val app = context.applicationContext
            val dao = ReminderDatabase.get(app).reminderDao()
            val rescheduler = Rescheduler(app)
            val now = System.currentTimeMillis()
            val here = ZoneId.systemDefault()
            val existing = id?.let { dao.byId(it) ?: return@withContext null }
            val times = timesColumn(spec.times)
            val draft = Reminder(
                id = existing?.id ?: 0,
                title = spec.title,
                note = spec.note,
                rawInput = existing?.rawInput ?: rawInput ?: spec.title,
                nextTriggerAt = 0,
                rrule = spec.rrule,
                zoneId = here.id,
                wallClockAnchored = spec.wallClockAnchored,
                parsedBy = parsedBy ?: existing?.parsedBy,
                day = spec.day,
                times = times,
                remindBefore = remindBeforeColumn(spec.remindBefore),
                // 不定钟点的一律只发通知（§4.6）
                ring = if (times == null) Ring.NOTICE else spec.ring,
                nag = spec.nag,
                slot = times?.substringBefore(','),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
            val check = CheckTime.get(app)
            val zone = Schedule.zoneOf(draft, here)
            val beat = Schedule.start(draft, now, check, zone) ?: Schedule.firstMain(draft, check, zone)
            val armed = draft.copy(
                day = beat.day, slot = beat.slot?.toString(), phase = beat.phase, nagCount = beat.nagCount, nextTriggerAt = beat.at
            )
            val saved = if (existing == null) {
                armed.copy(id = dao.insert(armed))
            } else {
                rescheduler.cancel(existing.id)
                Notifier.cancel(app, existing.id)
                dao.update(armed)
                armed
            }
            rescheduler.schedule(saved)
            saved
        }

    /** 模型删的（`delete_reminders`），一次几条。返回真删了的（库里没有的跳过）。删法同 [delete] */
    suspend fun deleteAll(context: Context, ids: List<Long>): List<Reminder> = withContext(NonCancellable) {
        val app = context.applicationContext
        val dao = ReminderDatabase.get(app).reminderDao()
        val rescheduler = Rescheduler(app)
        val deleted = ids.mapNotNull { dao.byId(it) }.onEach { r ->
            rescheduler.cancel(r.id)
            Notifier.cancel(app, r.id)
            dao.delete(r)
            ReminderDatabase.get(app).itemDao().deleteOf(r.id)
        }
        if (deleted.isNotEmpty()) WidgetUpdater.refresh(app)
        deleted
    }

    /** 库里全部提醒，不分状态（模型查提醒用） */
    suspend fun all(context: Context): List<Reminder> =
        ReminderDatabase.get(context.applicationContext).reminderDao().observeAll().first()

    /**
     * 完成「这一次」（§4.6，用户 11-16 定的）：在哪儿点都一样 —— 通知、全屏页、提醒页的圈、小组件的 ✓、桌面卡片的圈。
     * 一次性的办完；重复的排下一次（先排它的提前那几下），不再整串停掉。
     *
     * [fired] 是通知上那一下属于哪一次：这条已经翻到别的次了（重复的响完就翻过去），只收通知，不把下一次也算掉。
     * 先撤闹钟再改状态 —— 反了会留下一个指向 DONE 记录的闹钟，到点照响。
     */
    suspend fun complete(context: Context, reminder: Reminder, fired: Fired? = null) {
        val app = context.applicationContext
        Notifier.cancel(app, reminder.id)
        val dao = ReminderDatabase.get(app).reminderDao()
        // 和到点、重算排成一队（FireHandler.lock），手上这份可能是旧的，轮到了再读一遍
        FireHandler.lock.withLock {
            val r = dao.byId(reminder.id) ?: return@withLock
            if (fired != null && !fired.sameOccurrence(r)) return@withLock
            val rescheduler = Rescheduler(app)
            val now = System.currentTimeMillis()
            when (r.status) {
                ReminderStatus.SCHEDULED -> {
                    val next = Schedule.afterDone(r, now, CheckTime.get(app), Schedule.zoneOf(r))
                    if (next != null) {
                        rescheduler.arm(r, next)
                    } else {
                        rescheduler.cancel(r.id)
                        dao.setStatus(r.id, ReminderStatus.DONE, now)
                    }
                }
                ReminderStatus.FIRED -> dao.setStatus(r.id, ReminderStatus.DONE, now)
                else -> Unit
            }
        }
        WidgetUpdater.refresh(app)
    }

    /**
     * 「稍后 10 分钟」：刚响的那一下（[fired]）挪到十分钟后再响一次，响完照它顶替的那一下往后排（§4.6）。
     * 这条下一下本来就排在十分钟以内（几个钟点挨得近、下一次的提前提醒），不另挪，下一下照常来
     */
    suspend fun snooze(context: Context, id: Long, fired: Fired?) {
        val app = context.applicationContext
        Notifier.cancel(app, id)
        val dao = ReminderDatabase.get(app).reminderDao()
        FireHandler.lock.withLock {
            val r = dao.byId(id) ?: return@withLock
            val now = System.currentTimeMillis()
            val at = now + SNOOZE_MS
            val beat = fired ?: Fired.of(r)
            val sooner = r.status == ReminderStatus.SCHEDULED && !beat.sameOccurrence(r) && r.nextTriggerAt <= at
            if (!sooner) {
                val rescheduler = Rescheduler(app)
                rescheduler.cancel(id)
                val phase = if (beat.phase == Phase.NUDGE) Phase.MAIN else beat.phase
                dao.snooze(id, beat.day, beat.slot, phase, beat.nagCount, at, now)
                dao.byId(id)?.let { rescheduler.schedule(it) }
            }
        }
        WidgetUpdater.refresh(app)
    }

    /**
     * 「不再提醒」（§4.6）：整串停掉，记录留着，在提醒页落成划掉的过去一行。
     * 和「完成」分开：完成只算这一次。先撤闹钟再改状态，理由同 [complete]
     */
    suspend fun stop(context: Context, r: Reminder) {
        stopAll(context, listOf(r.id))
    }

    /** 对话里的「不再提醒」（`stop_reminders`）：还排着的才停，返回停了的 */
    suspend fun stopAll(context: Context, ids: List<Long>): List<Reminder> {
        val app = context.applicationContext
        val dao = ReminderDatabase.get(app).reminderDao()
        val stopped = FireHandler.lock.withLock {
            ids.mapNotNull { id ->
                dao.byId(id)?.takeIf { it.status == ReminderStatus.SCHEDULED }?.also { r ->
                    Rescheduler(app).cancel(r.id)
                    dao.setStatus(r.id, ReminderStatus.CANCELLED, System.currentTimeMillis())
                }
            }
        }
        stopped.forEach { Notifier.cancel(app, it.id) }
        WidgetUpdater.refresh(app)
        return stopped
    }

    /** 删一条，清单一起删。返回删掉的清单，撤销时连它一起放回去（[restore]） */
    suspend fun delete(context: Context, r: Reminder): List<ReminderItem> {
        val app = context.applicationContext
        Rescheduler(app).cancel(r.id)
        Notifier.cancel(app, r.id)
        val db = ReminderDatabase.get(app)
        val items = db.itemDao().of(r.id)
        db.reminderDao().delete(r)
        db.itemDao().deleteOf(r.id)
        WidgetUpdater.refresh(app)
        return items
    }

    /**
     * 列表页的「撤销」：把完成 / 删除之前那一整条原样放回去。
     * 删除是当场真删的（不是等撤销过期再删）—— 进程在这几秒里被杀，结果也只是「删了」，
     * 不会留下一条库里有、闹钟没有的记录。放回来之后照常排闹钟；已经过点的交给巡检补发。
     */
    suspend fun restore(context: Context, r: Reminder, items: List<ReminderItem> = emptyList()) {
        val app = context.applicationContext
        val dao = ReminderDatabase.get(app).reminderDao()
        if (dao.byId(r.id) != null) dao.update(r) else dao.insert(r)
        if (items.isNotEmpty()) ReminderDatabase.get(app).itemDao().insertAll(items)
        val rescheduler = Rescheduler(app)
        rescheduler.cancel(r.id)
        if (r.status == ReminderStatus.SCHEDULED && r.nextTriggerAt > System.currentTimeMillis()) {
            rescheduler.schedule(r)
        }
        WidgetUpdater.refresh(app)
    }

    // ---------------- 清单（§4.5）：改动当场存，不等「记下改动」 ----------------

    private fun itemDao(context: Context) = ReminderDatabase.get(context.applicationContext).itemDao()

    suspend fun itemsOf(context: Context, reminderId: Long): List<ReminderItem> = itemDao(context).of(reminderId)

    suspend fun allItems(context: Context): List<ReminderItem> = itemDao(context).all()

    /** 接在已有的后面加几样（字、勾没勾），满了 [Checklist.MAX_ITEMS] 的不加 */
    suspend fun addItems(context: Context, reminderId: Long, items: List<Pair<String, Boolean>>) {
        if (items.isEmpty()) return
        val dao = itemDao(context)
        val have = dao.of(reminderId)
        var next = (have.maxOfOrNull { it.position } ?: -1) + 1
        val add = items.mapNotNull { (text, done) -> Checklist.clean(text)?.let { it to done } }
            .take((Checklist.MAX_ITEMS - have.size).coerceAtLeast(0))
            .map { (text, done) -> ReminderItem(reminderId = reminderId, text = text, done = done, position = next++) }
        if (add.isNotEmpty()) {
            dao.insertAll(add)
            WidgetUpdater.refresh(context.applicationContext)
        }
    }

    // 清单一改，桌面卡片上绑了它的跟着变（§8.5）：每一处都喊一声

    suspend fun setItemDone(context: Context, item: ReminderItem, done: Boolean) {
        itemDao(context).update(item.copy(done = done))
        WidgetUpdater.refresh(context.applicationContext)
    }

    /** 改字；改成空的就是删 */
    suspend fun renameItem(context: Context, item: ReminderItem, text: String) {
        val clean = Checklist.clean(text)
        when {
            clean == null -> itemDao(context).delete(item.id)
            clean != item.text -> itemDao(context).update(item.copy(text = clean))
            else -> return
        }
        WidgetUpdater.refresh(context.applicationContext)
    }

    suspend fun removeItem(context: Context, item: ReminderItem) {
        itemDao(context).delete(item.id)
        WidgetUpdater.refresh(context.applicationContext)
    }

    /** 编辑页左滑删了一样又点「撤销」：原样放回原位 */
    suspend fun restoreItem(context: Context, item: ReminderItem) {
        itemDao(context).insert(item)
        WidgetUpdater.refresh(context.applicationContext)
    }

    /** 对话里改清单（`change_checklist`）。没有这一条是 null；返回改了什么，和改完的整份 */
    suspend fun editItems(context: Context, reminderId: Long, edit: ChecklistEdit): Pair<ChecklistEdit.Plan, List<ReminderItem>>? {
        val app = context.applicationContext
        ReminderDatabase.get(app).reminderDao().byId(reminderId) ?: return null
        val dao = itemDao(app)
        val plan = edit.plan(dao.of(reminderId))
        plan.checked.forEach { dao.update(it.copy(done = true)) }
        plan.unchecked.forEach { dao.update(it.copy(done = false)) }
        plan.removed.forEach { dao.delete(it.id) }
        addItems(app, reminderId, plan.added.map { it to false })
        WidgetUpdater.refresh(app)
        return plan to dao.of(reminderId)
    }

    suspend fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        Rescheduler(app).rescheduleAll()
        WidgetUpdater.refresh(app)
    }

    /** 收尾时刻（「没说几点的，几点提醒」）。改完排着的那几下跟着挪（[Rescheduler.realignAll]） */
    suspend fun setCheckTime(context: Context, time: LocalTime) {
        val app = context.applicationContext
        CheckTime.save(app, time)
        Rescheduler(app).realignAll(time)
        WidgetUpdater.refresh(app)
    }

    suspend fun clearLogs(context: Context) = ReminderDatabase.get(context).fireLogDao().clear()

    /**
     * M1 的出口条件：20 条覆盖未来 48 小时、必然包含凌晨时段的提醒。
     * 排完就把手机揣兜里别碰，48 小时后回来看日志。见设计文档 §9.3（眼下没有入口，要跑时临时接一个按钮）
     */
    suspend fun startSoakTest(context: Context) {
        val app = context.applicationContext
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val step = TimeUnit.HOURS.toMillis(48) / 20
        repeat(20) { i ->
            val at = Instant.ofEpochMilli(now + step * (i + 1)).atZone(zone)
            save(
                app, null,
                ReminderSpec(title = "放置测试 #${i + 1}", day = at.toLocalDate(), times = listOf(at.toLocalTime().withSecond(0).withNano(0))),
                rawInput = "soak", parsedBy = null
            )
        }
        WidgetUpdater.refresh(app)
    }
}

package com.abc.daodian.reminder.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.abc.daodian.intake.Notice
import com.abc.daodian.intake.NoticeSubscriber
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.dueDate
import com.abc.daodian.reminder.domain.ReminderText
import com.abc.daodian.reminder.domain.Rrule
import com.abc.daodian.shared.apps.AppCatalog
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.navigation.Launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 派活：名单上的人在微信里给你发一句，这里接住，交给模型建成提醒。
 *
 * 它是通知监听层的一个订阅者（DESIGN.md §2.3）：你给派活勾的 app（微信），通知才会交到这里。
 * 更细的规则是这里自己的：只认通知标题是名单（[RelaySettings]）上某个人名字的那些，微信的「[3条]」「备注名: 」自己剥；
 * 这些人说的每一句都记进 `relay.db`，那个人设了暗号的只把暗号开头的交给模型（[RelayAgent]），没设就句句都交。
 * 叫模型是重活，[accept] 只落库，模型另起一个协程办。建成了弹一条通知告诉你，点开是那条提醒。
 */
object Relay : NoticeSubscriber {

    override val id = "relay"

    override val label = "派活"

    override val purpose = "勾上的 app 里，派活名单上的人发来的话记下来，交给模型建成提醒。别人发的只记名字（在内存里，给你挑人），不存、不发给模型。"

    /** 派活听的是聊天：聊天软件、短信排前面 */
    override fun suggested(context: Context, pkg: String): Boolean = AppCatalog.isChat(pkg) || AppCatalog.isSms(context, pkg)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一句一句办，别两句同时叫模型 */
    private val lock = Mutex()

    private val _seen = MutableStateFlow<List<String>>(emptyList())

    /** 最近在勾上的 app 里发过消息的人（通知标题），加人、改名字时给你挑。只在内存里、只记名字 */
    val seen: StateFlow<List<String>> = _seen.asStateFlow()

    /** 监听层只会交来给派活勾了的 app 的通知，包名不用再看 */
    override suspend fun accept(context: Context, notice: Notice) {
        val title = notice.title?.let(::cleanTitle)?.takeIf { it.isNotBlank() } ?: return
        _seen.update { (listOf(title) + it).distinct().take(SEEN_MAX) }
        val person = RelaySettings.read(context).firstOrNull { it.name == title } ?: return
        val text = cleanText(notice.text, title) ?: return
        receive(context, notice.pkg, title, text, notice.at, notice.how, person.code)
    }

    private suspend fun receive(context: Context, pkg: String, who: String, text: String, at: Long, how: String, code: String) {
        val app = context.applicationContext
        val skip = when {
            NON_TEXT.containsMatchIn(text) -> "不是文字"
            code.isNotEmpty() && !text.startsWith(code, ignoreCase = true) -> "没带暗号"
            else -> null
        }
        val row = RelayMessage(
            pkg = pkg, who = who, text = text, at = at, receivedAt = System.currentTimeMillis(), how = how,
            fingerprint = "$pkg|$who|$at|$text",
            status = (if (skip != null) RelayStatus.SKIPPED else RelayStatus.WORKING).name,
            detail = skip
        )
        val id = RelayDatabase.get(app).dao().insert(row)
        if (id == -1L || skip != null) return
        scope.launch { work(app, id, code) }
    }

    /** 没办成的、办到一半进程没了的，再交一次。暗号按那个人眼下设的算 */
    fun retry(context: Context, id: Long) {
        val app = context.applicationContext
        scope.launch {
            val m = RelayDatabase.get(app).dao().byId(id) ?: return@launch
            work(app, id, personOf(app, m.who)?.code.orEmpty())
        }
    }

    private suspend fun personOf(context: Context, who: String): RelaySettings.Person? =
        RelaySettings.read(context).firstOrNull { it.name == who }

    private suspend fun work(context: Context, id: Long, code: String) = lock.withLock {
        val dao = RelayDatabase.get(context).dao()
        val m = dao.byId(id) ?: return@withLock
        dao.update(m.copy(status = RelayStatus.WORKING.name, detail = null))
        val coded = code.isNotEmpty() && m.text.startsWith(code, ignoreCase = true)
        val task = if (coded) m.text.drop(code.length).trimStart(*LEAD) else m.text
        val outcome = runCatching { RelayAgent.run(context, m, task, coded) }
            .getOrElse { RelayAgent.Outcome.Failed(it.message ?: it.javaClass.simpleName) }
        val done = when (outcome) {
            is RelayAgent.Outcome.Created -> {
                val reminders = outcome.ids.mapNotNull { ReminderDatabase.get(context).reminderDao().byId(it) }
                val said = reminders.joinToString("；") { "${whenOf(it)} · ${it.title}" }
                notify(context, m, "${m.who}派了一件事", said, Launch.intent(context, route = ReminderRoutes.edit(outcome.ids.first())))
                m.copy(status = RelayStatus.CREATED.name, detail = said, reminderId = outcome.ids.first())
            }
            is RelayAgent.Outcome.NotTask -> m.copy(status = RelayStatus.NOT_TASK.name, detail = outcome.reply)
            is RelayAgent.Outcome.Failed -> {
                // 用了暗号还没接住，得让你知道；没暗号的闲聊办不成就算了，不吵你。点开是这个人那一页
                if (coded) {
                    val route = personOf(context, m.who)?.let { ReminderRoutes.relay(it.id) } ?: ReminderRoutes.SETTINGS
                    notify(context, m, "${m.who}派的事没接住", outcome.why, Launch.intent(context, route = route))
                }
                m.copy(status = RelayStatus.FAILED.name, detail = outcome.why)
            }
        }
        dao.update(done)
    }

    /** 「10月1日 周四 15:00」「今天之内」「每天 08:00」 */
    private fun whenOf(r: Reminder): String {
        val repeat = Rrule.human(r.rrule)
        val day = r.dueDate()
        return when {
            day != null && repeat != null -> "$repeat · 当天之内"
            day != null -> ReminderText.dayTaskWhen(day)
            repeat != null -> "$repeat ${Format.clock(r.nextTriggerAt)}"
            else -> Format.humanDateTime(r.nextTriggerAt)
        }
    }

    private fun notify(context: Context, m: RelayMessage, title: String, text: String, open: android.content.Intent) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "别人派的事", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "名单上的人在微信里派了事、建成了提醒时告诉你"
            }
        )
        val pi = PendingIntent.getActivity(
            context, (NOTIFY_BASE + m.id).toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n「${m.text}」"))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify((NOTIFY_BASE + m.id).toInt(), n) }
    }

    /** 标题后面带的未读数：「小美(2条新消息)」 */
    private val TITLE_COUNT = Regex("""\s*[（(]\d+\s*条[^）)]*[）)]\s*$""")

    /** 正文前面的未读数：「[2条]」 */
    private val TEXT_COUNT = Regex("""^\s*\[\d+\s*条]\s*""")

    /**
     * 图片、语音、红包、链接……：微信写成「[图片]」开头。小表情也是方括号（「[呲牙]明天取快递」），
     * 所以只认这几样，或者整句就一个方括号
     */
    private val NON_TEXT = Regex("""^\[(图片|语音|视频|动画表情|表情|文件|链接|位置|微信红包|红包|转账|名片|小程序|视频号|音乐|聊天记录|语音通话|视频通话)]|^\[[^\]]{1,8}]$""")

    /** 暗号后面跟的标点、空白 */
    private val LEAD = charArrayOf(' ', '　', ':', '：', ',', '，', '、', '.', '。', '!', '！', '-', '—')

    private fun cleanTitle(title: String): String = title.replace(TITLE_COUNT, "").trim()

    /** 去掉「[2条]」和群聊里的「小美: 」。空的是 null */
    private fun cleanText(text: String?, who: String): String? {
        var t = text?.replace(TEXT_COUNT, "")?.trim() ?: return null
        for (sep in listOf(": ", "：", ":")) if (t.startsWith(who + sep)) { t = t.removePrefix(who + sep).trim(); break }
        return t.ifBlank { null }
    }

    private const val SEEN_MAX = 12
    private const val CHANNEL_ID = "relay_v1"
    private const val NOTIFY_BASE = 700_000L
}

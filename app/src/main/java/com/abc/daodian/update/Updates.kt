package com.abc.daodian.update

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.abc.daodian.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内测的检查更新。新包和一份 `latest.json` 放在官网那台服务器的 `/app/` 下（`scripts/publish.sh` 传上去）。
 *
 * 只是「知道有新版、点一下装上」：同一个签名、versionCode 更大，系统装的时候就是覆盖升级，数据都在。
 * 下载交给系统的 DownloadManager（离开 app 也接着下，通知栏有进度），下完交给系统安装器。
 * 下载 id 记在 prefs 里：进程被收掉再开，下完的直接装、没下完的接着盯，不重下；
 * 下完时 app 在后台，系统不让弹安装器，回到前台再弹（[foreground]）。
 * 什么时候查：开 app、回到设置页、点「版本」那行，外加每天 09:00 / 13:00 / 19:00 在后台查一次（[DailyCheck]）。
 * debug 包不查：包名不同，装 release 包是另装一个 app。
 */
object Updates {

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val notes: String,
        val size: Long
    )

    sealed interface State {
        /** 没查过、查不到（没网、服务器没回）都算这个：查不到不打扰 */
        data object Unknown : State
        data object Latest : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val fraction: Float?) : State
        /** 包已经下完整了，点一下就装；进程重开也认得（下载 id 记在 prefs 里），不重下 */
        data class Ready(val release: Release) : State
        data class Failed(val release: Release, val why: String) : State
    }

    val enabled: Boolean = !BuildConfig.DEBUG && BuildConfig.UPDATE_FEED.isNotBlank()

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastCheck = 0L
    private var downloadId: Long? = null

    /** 冷启动和回到设置页时调。一小时内查过就不再查，[force] 除外。每天几个钟点另在后台查（[DailyCheck]） */
    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!enabled) return
        if (_state.value is State.Downloading || _state.value is State.Ready) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60 * 60_000L) return
        lastCheck = now
        scope.launch { refresh(app) }
    }

    /** 真去查一次，返回查完的状态；查不到（没网、服务器没回）是 null。后台定时那次在 Worker 里等着它 */
    suspend fun refresh(app: Context): State? = withContext(Dispatchers.IO) {
        val release = runCatching { fetch() }.getOrNull() ?: return@withContext null
        val before = _state.value
        if (before is State.Downloading || before is State.Ready) return@withContext before
        _state.value = if (release.versionCode > BuildConfig.VERSION_CODE) resume(app, release) else {
            clear(app)
            DailyCheck.dismiss(app)
            State.Latest
        }
        // 上回下完时 app 不在前台、后来进程又被收掉：这次开 app 补弹一次
        val s = _state.value
        if (s is State.Ready && foreground && !prefs(app).getBoolean(KEY_PROMPTED, true)) prompt(app, s.release)
        s
    }

    private fun fetch(): Release {
        val conn = URL(BuildConfig.UPDATE_FEED).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.useCaches = false
        try {
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            return Release(
                versionCode = o.getInt("versionCode"),
                versionName = o.getString("versionName"),
                url = o.getString("url"),
                notes = o.optString("notes"),
                size = o.optLong("size")
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 点了「下载更新」。还没许「安装未知应用」就先去系统那页开（返回 true 表示去开了），回来再点一次。
     */
    fun download(context: Context, release: Release): Boolean {
        val app = context.applicationContext
        if (askInstallPermission(context)) return true
        // 后台查到时发的那条「有新版本」用不着了：下载自己有进度通知
        DailyCheck.dismiss(app)
        val dm = app.getSystemService(DownloadManager::class.java)
        // 上一次下的旧包、没下完的那条先清掉，免得 DownloadManager 改名成 -1.apk
        prefs(app).getLong(KEY_ID, -1L).takeIf { it >= 0 }?.let { dm.remove(it) }
        downloadDir(app)?.listFiles()?.forEach { it.delete() }
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(release.url))
                .setTitle("到点 ${release.versionName}")
                .setMimeType(APK)
                // 下完通知留着：app 被系统收掉了，点这条也能装
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName(release))
        )
        // 记进 prefs：下载途中 app 进程被收掉（荣耀常干），回来接着盯这一条，不重下
        prefs(app).edit()
            .putLong(KEY_ID, id)
            .putInt(KEY_VERSION, release.versionCode)
            .putBoolean(KEY_PROMPTED, false)
            .apply()
        follow(app, dm, id, release)
        return false
    }

    /** 下好了，点「版本」那行：交给系统安装器 */
    fun install(context: Context, release: Release) {
        if (askInstallPermission(context)) return
        val file = apk(context.applicationContext, release) ?: run {
            _state.value = State.Available(release)
            return
        }
        openInstaller(context, file)
    }

    /**
     * MainActivity 的 onResume / onPause 报。下完那一刻 app 在后台的话，系统不让后台弹安装器
     * （Android 10 起拦，不报错、什么也不出），等回到前台再弹。
     */
    fun foreground(activity: Activity, shown: Boolean) {
        foreground = shown
        if (!shown) return
        val s = _state.value as? State.Ready ?: return
        if (prefs(activity).getBoolean(KEY_PROMPTED, true)) return
        prompt(activity, s.release)
    }

    @Volatile private var foreground = false

    private fun askInstallPermission(context: Context): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return false
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    /** 冷启动查到新版时：上回下的那条要么下完了、要么还在下，都不重下 */
    private fun resume(app: Context, release: Release): State {
        val p = prefs(app)
        val id = p.getLong(KEY_ID, -1L)
        if (id >= 0 && p.getInt(KEY_VERSION, 0) == release.versionCode) {
            val dm = app.getSystemService(DownloadManager::class.java)
            when (progress(dm, id).first) {
                DownloadManager.STATUS_SUCCESSFUL -> if (apk(app, release) != null) return State.Ready(release)
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                    follow(app, dm, id, release)
                    return _state.value
                }
            }
        }
        return State.Available(release)
    }

    /** 已经是最新：升级前下的那个包（八九十 MB）和那条下载记录都不用留了 */
    private fun clear(app: Context) {
        val p = prefs(app)
        val id = p.getLong(KEY_ID, -1L)
        if (id < 0) return
        runCatching { app.getSystemService(DownloadManager::class.java).remove(id) }
        downloadDir(app)?.listFiles()?.forEach { it.delete() }
        p.edit().clear().apply()
    }

    /** 盯着下载进度，下完交给安装器 */
    private fun follow(app: Context, dm: DownloadManager, id: Long, release: Release) {
        downloadId = id
        _state.value = State.Downloading(release, null)
        scope.launch {
            while (downloadId == id) {
                val (status, fraction) = progress(dm, id)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        downloadId = null
                        if (apk(app, release) == null) {
                            _state.value = State.Failed(release, "下下来的包不对，再点一次试试")
                            return@launch
                        }
                        _state.value = State.Ready(release)
                        if (foreground) prompt(app, release)
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        downloadId = null
                        _state.value = State.Failed(release, "没下下来，再点一次试试")
                        return@launch
                    }
                    else -> _state.value = State.Downloading(release, fraction)
                }
                delay(500)
            }
        }
    }

    /** 下完自动弹一次安装器；用户在安装器里点了取消，就留在「下好了」，不再追着弹 */
    private fun prompt(context: Context, release: Release) {
        val file = apk(context.applicationContext, release) ?: return
        prefs(context).edit().putBoolean(KEY_PROMPTED, true).apply()
        openInstaller(context, file)
    }

    private fun openInstaller(context: Context, file: File) {
        // 自己的 FileProvider 给安装器读：DownloadManager 给的 content://downloads 在有的 ROM 上安装器读不了
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diag", file)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 状态和进度；查不到这一条算失败 */
    private fun progress(dm: DownloadManager, id: Long): Pair<Int, Float?> =
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return@use null
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) to
                (if (total > 0) done.toFloat() / total else null)
        } ?: (DownloadManager.STATUS_FAILED to null)

    /** 这一版下完整了的包；没有、或大小和 `latest.json` 登记的对不上，就是 null */
    private fun apk(app: Context, release: Release): File? =
        downloadDir(app)?.let { File(it, fileName(release)) }
            ?.takeIf { it.isFile && it.length() > 0 && (release.size <= 0 || it.length() == release.size) }

    private fun downloadDir(app: Context): File? = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    private fun fileName(release: Release) = "daodian-${release.versionName}.apk"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("update", Context.MODE_PRIVATE)

    private const val APK = "application/vnd.android.package-archive"
    private const val KEY_ID = "download_id"
    private const val KEY_VERSION = "download_version"
    private const val KEY_PROMPTED = "install_prompted"
}

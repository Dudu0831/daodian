package com.abc.daodian.agent.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import com.abc.daodian.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内测的检查更新。新包和一份 `latest.json` 放在官网那台服务器的 `/app/` 下（`scripts/publish.sh` 传上去）。
 *
 * 只是「知道有新版、点一下装上」：同一个签名、versionCode 更大，系统装的时候就是覆盖升级，数据都在。
 * 下载交给系统的 DownloadManager（离开 app 也接着下，通知栏有进度），下完交给系统安装器。
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
        data class Failed(val release: Release, val why: String) : State
    }

    val enabled: Boolean = !BuildConfig.DEBUG && BuildConfig.UPDATE_FEED.isNotBlank()

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastCheck = 0L
    private var downloadId: Long? = null

    /** 冷启动和回到设置页时调。一小时内查过就不再查，[force] 除外 */
    fun check(force: Boolean = false) {
        if (!enabled) return
        if (_state.value is State.Downloading) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60 * 60_000L) return
        lastCheck = now
        scope.launch {
            val release = runCatching { fetch() }.getOrNull() ?: return@launch
            if (_state.value is State.Downloading) return@launch
            _state.value = if (release.versionCode > BuildConfig.VERSION_CODE) State.Available(release) else State.Latest
        }
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
        if (!app.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        }
        val dm = app.getSystemService(DownloadManager::class.java)
        val name = "daodian-${release.versionName}.apk"
        // 上一次下的旧包先删掉，免得 DownloadManager 改名成 -1.apk
        app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()?.forEach { it.delete() }
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(release.url))
                .setTitle("到点 ${release.versionName}")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, name)
        )
        downloadId = id
        _state.value = State.Downloading(release, 0f)
        scope.launch { follow(app, dm, id, release) }
        return false
    }

    /** 盯着下载进度，下完交给安装器 */
    private suspend fun follow(app: Context, dm: DownloadManager, id: Long, release: Release) {
        while (downloadId == id) {
            val (status, done, total) = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) return@use null
                Triple(
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                )
            } ?: Triple(DownloadManager.STATUS_FAILED, 0L, 0L)
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    downloadId = null
                    _state.value = State.Available(release)
                    install(app, dm, id)
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    downloadId = null
                    _state.value = State.Failed(release, "没下下来，再点一次试试")
                    return
                }
                else -> _state.value = State.Downloading(release, if (total > 0) done.toFloat() / total else null)
            }
            delay(500)
        }
    }

    private fun install(app: Context, dm: DownloadManager, id: Long) {
        val uri = dm.getUriForDownloadedFile(id) ?: return
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

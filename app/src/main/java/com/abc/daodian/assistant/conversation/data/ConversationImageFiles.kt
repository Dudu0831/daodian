package com.abc.daodian.assistant.conversation.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 对话里随话发的图片，在本机怎么放。见 DESIGN.md §6.10
 *
 * 选好、还没发的放在缓存目录（[pendingDir]），发出去那一刻挪进 [dir]，之后一直留着 —— 对话记录里要画。
 * 进来时就缩小、转成 JPEG：发给模型的和界面上画的是同一份，不留原图。
 * 文件名里带着宽高（`…_960x1280.jpg`），气泡排版时不用先解码就知道占多大，图解出来那一下列表不会跳。
 */
object ConversationImageFiles {

    /** 一句话最多带几张 */
    const val MAX_PER_MESSAGE = 4

    /** 长边缩到这么多像素。再大，每一步重传的流量和 token 都不值 */
    private const val LONG_EDGE = 1280
    private const val JPEG_QUALITY = 82

    private val SIZE_IN_NAME = Regex("_(\\d+)x(\\d+)\\.jpg$")

    /** 发出去的图。跟 `chat.db` 一样只增不减 */
    fun dir(context: Context): File = File(context.applicationContext.filesDir, "chat_images")

    /** 选好、还没发的。对话页的状态没了它们也就没用了，下回打开时清掉（[clearStale]） */
    private fun pendingDir(context: Context): File = File(context.applicationContext.cacheDir, "chat_pending")

    /** 相机拍的原片先落在这儿，缩完就删 */
    private fun cameraDir(context: Context): File = File(context.applicationContext.cacheDir, "chat_camera")

    /**
     * 相册选的、相机拍的一张（[uri]）读进来：缩小、摆正、存成 JPEG，放进待发的那一行。
     * 读不了（文件坏了、格式不认）返回 null
     */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            // ImageDecoder 自己按 EXIF 把方向摆正，HEIC、WebP 也认
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = LONG_EDGE.toFloat() / max(info.size.width, info.size.height)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1)
                    )
                }
            }
            val dir = pendingDir(context).apply { mkdirs() }
            val file = File(dir, "${System.currentTimeMillis()}-${(1000..9999).random()}_${bitmap.width}x${bitmap.height}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bitmap.recycle()
            file.path
        }.getOrNull()
    }

    /**
     * 发出去了：从待发挪进 [dir]。已经在里面的（喊停退回来又重发的）原样返回；
     * 挪不动的（文件没了）就不带这一张，别为它把整句话卡住
     */
    fun commit(context: Context, paths: List<String>): List<String> {
        val dir = dir(context).apply { mkdirs() }
        return paths.mapNotNull { path ->
            val from = File(path)
            if (from.parentFile == dir) return@mapNotNull path
            val to = File(dir, from.name)
            runCatching {
                if (!from.renameTo(to)) {
                    from.copyTo(to, overwrite = true)
                    from.delete()
                }
                to.path
            }.getOrNull()
        }
    }

    /** 这张不要了（待发那一行上点了叉） */
    fun delete(path: String) {
        File(path).delete()
    }

    /**
     * 清掉没人要的：待发目录和相机落片目录里一分钟以前的文件。对话页的状态新建时调一次。
     * 留一分钟的余地：页面被系统回收、从相册或相机回来时状态是新建的，刚拿到手的那张不能被这一下清掉
     */
    fun clearStale(context: Context) {
        val before = System.currentTimeMillis() - 60_000
        listOf(pendingDir(context), cameraDir(context)).forEach { dir ->
            dir.listFiles()?.filter { it.lastModified() < before }?.forEach { it.delete() }
        }
    }

    /** 给系统相机一个落片的地方。拍完把 [Uri] 交给 [import]，再 [delete] 掉这个文件 */
    fun cameraTarget(context: Context): Pair<File, Uri> {
        val file = File(cameraDir(context).apply { mkdirs() }, "shot-${System.currentTimeMillis()}.jpg")
        return file to FileProvider.getUriForFile(context, "${context.packageName}.diag", file)
    }

    /** 宽 / 高，从文件名里读。读不出来（不是这里存的文件）当方的 */
    fun aspect(path: String): Float {
        val m = SIZE_IN_NAME.find(path) ?: return 1f
        val w = m.groupValues[1].toFloatOrNull() ?: return 1f
        val h = m.groupValues[2].toFloatOrNull() ?: return 1f
        return if (w > 0 && h > 0) w / h else 1f
    }

    /** 解出一张长边不小于 [minPx] 的（用不着整张解的时候省内存）。文件没了返回 null */
    fun decode(path: String, minPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= minPx) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

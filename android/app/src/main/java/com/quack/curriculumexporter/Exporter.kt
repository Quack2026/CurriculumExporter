package com.quack.curriculumexporter

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 把 .ics 落到「下载/课表导出/」。
 *
 * 全程走 MediaStore：
 * - Android 10+ 用 `MediaStore.Downloads`，不需要任何存储权限；
 * - Android 9 及以下退回公共 Download 目录 + `WRITE_EXTERNAL_STORAGE`。
 *
 * 两条路都拿到 content:// 的 uri，所以「分享」这一步不需要 FileProvider（也就不需要 androidx）。
 */
object Exporter {

    const val MIME = "text/calendar"
    const val SUB_DIR = "课表导出"

    /** 保存并返回可分享的 uri。 */
    fun save(context: Context, fileName: String, content: String): Uri {
        // UTF-8 无 BOM：Windows 版也是这么写的，部分手机日历对 BOM 会报解析错
        val bytes = content.toByteArray(Charsets.UTF_8)
        val resolver = context.contentResolver

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(resolver, fileName, bytes)
        } else {
            saveToPublicDownloads(context, fileName, bytes)
        }
    }

    /** 分享 .ics 的 intent（调用方记得套 createChooser）。 */
    fun shareIntent(uri: Uri, fileName: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** 形如 `广理课表-20260929-1930.ics`。 */
    fun fileName(): String =
        "广理课表-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".ics"

    // ---------------------------------------------------------------- 内部

    private fun saveViaMediaStore(
        resolver: android.content.ContentResolver,
        fileName: String,
        bytes: ByteArray,
    ): Uri {
        // MediaStore.Downloads 是 API 29 才有的常量。save() 已经按版本分过流，
        // 这里再兜一道：万一以后有人直接调这个方法，得到的是一句能看懂的错误，
        // 而不是在 Android 9 上抛 NoSuchFieldError。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("系统版本低于 Android 10，不能走 MediaStore 写入")
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("在「下载」目录里建文件失败")

        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IOException("打不开输出流")
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // 别把写了一半的 IS_PENDING 半成品留在相册/文件列表里
            throw IOException("写入文件失败：${e.message}")
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicDownloads(context: Context, fileName: String, bytes: ByteArray): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR,
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("建不了目录：${dir.absolutePath}")
        val file = File(dir, fileName)
        try {
            file.writeBytes(bytes)
        } catch (e: Exception) {
            throw IOException("写入文件失败：${e.message}")
        }

        // 登记进 MediaStore，这样返回的也是 content:// uri，分享出去才不会被 FileUriExposed 拦
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
        }
        return context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: Uri.fromFile(file)
    }
}

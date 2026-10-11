package com.dsh.offlineinterp.pipeline

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings as AndroidSettings
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新：查 GitHub Releases → 下载 APK → 交给系统安装器。
 *
 * 为什么这么绕：应用不在应用商店，只能自己更新。做法是
 *   1. CI 每次构建都把 APK 发成一个 Release（tag = 版本号）
 *   2. App 调 GitHub 的 releases/latest 接口拿最新 tag 和 APK 下载地址
 *   3. 下载到系统"下载"目录（用 MediaStore，**不需要任何存储权限**）
 *   4. 用 content:// URI 调系统安装器，你点一下"安装"即可
 *
 * 私有仓库需要你在设置页填一个**只读 token**（只存本机）。
 */
object Updater {

    data class ReleaseInfo(
        val tag: String,
        val version: String,
        val apkUrl: String,
        val notes: String
    )

    /** 返回 (更新信息, 错误信息) */
    fun check(repoApiUrl: String, token: String, timeoutMs: Int = 10_000): Pair<ReleaseInfo?, String> {
        if (repoApiUrl.isBlank()) return null to "更新源地址为空"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(repoApiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "offline-interp")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${token.trim()}")
                }
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code == 404) {
                return null to "还没发布过 Release（CI 里已配置自动发布，推一次代码就有了）"
            }
            if (code == 401 || code == 403) {
                return null to when {
                    token.isBlank() ->
                        "HTTP $code：Token 没填。私有仓库必须填只读 Token（用设置页的「粘贴 Token」按钮），" +
                            "或者把仓库改成公开就不需要 Token"
                    token.length < 20 ->
                        "HTTP $code：Token 只有 ${token.length} 个字符，像是没粘贴完整"
                    else ->
                        "HTTP $code：Token 无效或权限不足（需要 Contents: Read-only），也可能是已过期"
                }
            }
            if (code !in 200..299) {
                return null to "HTTP $code：${body.take(160)}"
            }

            val tag = grab(body, "tag_name") ?: return null to "解析不到 tag_name"
            val apkUrl = firstApkUrl(body) ?: return null to "该 Release 里没有 .apk 附件"
            val notes = grab(body, "body").orEmpty().take(300)
            ReleaseInfo(tag = tag, version = tag.removePrefix("v"), apkUrl = apkUrl, notes = notes) to ""
        } catch (t: Throwable) {
            null to "${t.javaClass.simpleName}：${t.message}"
        } finally {
            conn?.disconnect()
        }
    }

    private fun grab(json: String, key: String): String? {
        val k = "\"$key\""
        val i = json.indexOf(k)
        if (i < 0) return null
        var j = json.indexOf('"', i + k.length)
        if (j < 0) return null
        j++
        val start = j
        val sb = StringBuilder()
        while (j < json.length) {
            val c = json[j]
            if (c == '\\' && j + 1 < json.length) {
                when (json[j + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(json[j + 1])
                }
                j += 2
                continue
            }
            if (c == '"') return sb.toString()
            sb.append(c)
            j++
        }
        return if (j > start) sb.toString() else null
    }

    private fun firstApkUrl(json: String): String? {
        var index = json.indexOf("browser_download_url")
        while (index >= 0) {
            val url = grab(json.substring(index - 1), "browser_download_url")
            if (url != null && url.endsWith(".apk")) return url
            index = json.indexOf("browser_download_url", index + 1)
        }
        return null
    }

    /** 当前安装的版本号 */
    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    /**
     * 下载到系统下载目录并触发安装。
     * 用 MediaStore（API 29+ 无需存储权限）；返回 (是否成功, 说明)
     */
    fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        fileName: String,
        token: String,
        onProgress: (Long, Long) -> Unit
    ): Pair<Boolean, String> {
        var uri: Uri? = null
        var conn: HttpURLConnection? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return false to "无法在下载目录创建文件"

            conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "offline-interp")
                setRequestProperty("Accept", "application/octet-stream")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${token.trim()}")
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                context.contentResolver.delete(uri, null, null)
                return false to "下载失败 HTTP $code"
            }

            val total = conn.contentLengthLong
            var done = 0L
            context.contentResolver.openOutputStream(uri)?.use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            } ?: run {
                context.contentResolver.delete(uri, null, null)
                return false to "无法写入下载文件"
            }

            val done2 = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            context.contentResolver.update(uri, done2, null, null)

            install(context, uri)
            true to "已下载 ${done / 1024 / 1024} MB，请在系统弹窗里点「安装」"
        } catch (t: Throwable) {
            try {
                uri?.let { context.contentResolver.delete(it, null, null) }
            } catch (_: FileNotFoundException) {
            }
            false to "下载异常 ${t.javaClass.simpleName}：${t.message}"
        } finally {
            conn?.disconnect()
        }
    }

    /** 调系统安装器；如果没开"安装未知应用"权限，先带用户去开 */
    fun install(context: Context, apkUri: Uri): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apkUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (t: Throwable) {
            false
        }
    }
}

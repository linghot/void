package com.dsh.offlineinterp.pipeline

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 识别模型的管理：去哪儿放、下没下、怎么下。
 *
 * 模型不放 APK 里（否则安装包几百 MB），而是放到 App 的外部私有目录：
 *   /sdcard/Android/data/com.dsh.offlineinterp/files/models/asr/
 * 这个位置卸载即清、不需要存储权限。
 *
 * 下载地址来自 whisper.cpp 官方模型仓库（HuggingFace）。下完是纯离线运行，
 * 之后不再需要网络。
 */
object ModelStore {

    data class Model(
        val id: String,
        val fileName: String,
        val label: String,
        val sizeMb: Int,
        val url: String
    )

    val MODELS: List<Model> = listOf(
        Model(
            id = "tiny",
            fileName = "ggml-tiny.bin",
            label = "tiny · 约 75 MB · 最快，中文一般",
            sizeMb = 75,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
        ),
        Model(
            id = "base",
            fileName = "ggml-base.bin",
            label = "base · 约 142 MB · 推荐起点",
            sizeMb = 142,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
        ),
        Model(
            id = "small",
            fileName = "ggml-small.bin",
            label = "small · 约 466 MB · 中文更好，明显更慢",
            sizeMb = 466,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
        )
    )

    /** 默认用 base：中文与速度的折中点 */
    val DEFAULT: Model = MODELS[1]

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null), "models/asr").apply { if (!exists()) mkdirs() }

    fun file(context: Context, model: Model): File = File(dir(context), model.fileName)

    /** 文件存在且大小合理（防止半截文件被当成模型） */
    fun isReady(context: Context, model: Model): Boolean {
        val f = file(context, model)
        return f.isFile && f.length() > 10L * 1024 * 1024
    }

    fun sizeMb(context: Context, model: Model): Long =
        file(context, model).length() / (1024 * 1024)

    /**
     * 下载模型。会在工作线程执行（调用方负责），onProgress 回调总字节/已下载字节。
     * 先写 .part 再改名，避免下到一半被当成可用模型。
     */
    fun download(model: Model, target: File, onProgress: (Long, Long) -> Unit): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) {
                onProgress(-1, -1)
                return false
            }
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            true
        } catch (t: Throwable) {
            part.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }
}

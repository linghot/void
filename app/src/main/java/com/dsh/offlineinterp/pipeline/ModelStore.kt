package com.dsh.offlineinterp.pipeline

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 识别模型的管理：去哪儿放、下没下、怎么下、怎么手动导入。
 *
 * 三个关键设计（v0.5 起）：
 *  1. **国内镜像**：huggingface.co 在国内常被墙，所以每个模型都配了两个地址，
 *     官方源失败自动换 `hf-mirror.com`。
 *  2. **多目录查找**：除了 App 私有目录，还会去公共目录找（方便你用数据线/微信
 *     传进来的模型），顺序见 [candidates]。
 *  3. **手动导入**：`从文件导入模型` 走系统文件选择器（SAF），选中的文件会被复制进
 *     App 私有目录——完全不需要存储权限，也不受下载限制。
 *
 * 模型不放 APK 里（否则安装包几百 MB）。私有目录路径：
 *   /sdcard/Android/data/com.dsh.offlineinterp/files/models/asr/
 * 公共目录（手动放这里也能被识别）：
 *   /sdcard/Download/offlineinterp/
 *   /sdcard/Documents/offlineinterp/
 */
object ModelStore {

    /** 小于这个大小的一律当成没下完 */
    private const val MIN_BYTES = 10L * 1024 * 1024

    data class Model(
        val id: String,
        val fileName: String,
        val label: String,
        val sizeMb: Int,
        val urls: List<String>
    )

    private fun urlsOf(fileName: String): List<String> = listOf(
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName",
        "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/$fileName"
    )

    val MODELS: List<Model> = listOf(
        Model(
            id = "tiny",
            fileName = "ggml-tiny.bin",
            label = "tiny · 约 75 MB · 最快，中文一般（先求不卡就用它）",
            sizeMb = 75,
            urls = urlsOf("ggml-tiny.bin")
        ),
        Model(
            id = "base",
            fileName = "ggml-base.bin",
            label = "base · 约 142 MB · 均衡",
            sizeMb = 142,
            urls = urlsOf("ggml-base.bin")
        ),
        Model(
            id = "small",
            fileName = "ggml-small.bin",
            label = "small · 约 466 MB · 中文更好，明显更慢",
            sizeMb = 466,
            urls = urlsOf("ggml-small.bin")
        )
    )

    val DEFAULT: Model = MODELS[1]

    /** App 私有目录：下载目标位置 */
    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null), "models/asr").apply { if (!exists()) mkdirs() }

    /** 公共目录：你手动放模型的地方（按顺序查找） */
    fun manualDirs(context: Context): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOf(
            File(root, "Download/offlineinterp"),
            File(root, "Documents/offlineinterp"),
            File(root, "offlineinterp")
        )
    }

    /** 全部候选路径，按优先级排列 */
    fun candidates(context: Context, model: Model): List<File> =
        listOf(File(dir(context), model.fileName)) +
            manualDirs(context).map { File(it, model.fileName) }

    /** 找到可用的模型文件（大小合理才算） */
    fun resolveFile(context: Context, model: Model): File? =
        candidates(context, model).firstOrNull { it.isFile && it.length() > MIN_BYTES }

    fun isReady(context: Context, model: Model): Boolean = resolveFile(context, model) != null

    /** 下载目标（私有目录里的那个文件） */
    fun file(context: Context, model: Model): File = File(dir(context), model.fileName)

    fun sizeMb(file: File): Long = file.length() / (1024 * 1024)

    /**
     * 下载：依次尝试 [Model.urls] 里的每个地址，先写 .part 再改名。
     * [onLog] 用来把"正在试哪个源""为什么失败"回报到界面。
     */
    fun download(
        model: Model,
        target: File,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit = {}
    ): Boolean {
        for ((index, url) in model.urls.withIndex()) {
            onLog("尝试源 ${index + 1}/${model.urls.size}：$url")
            if (downloadFrom(url, target, onProgress, onLog)) return true
        }
        onLog("所有下载源都失败了")
        return false
    }

    private fun downloadFrom(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit
    ): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "offline-interp/0.5")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                onLog("  该源返回 HTTP $code")
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
            // 大小兜底：下到一半的 HTML 错误页不能当模型
            if (part.length() <= MIN_BYTES) {
                onLog("  下载内容过小（${part.length()} 字节），疑似被拦截或返回错误页")
                part.delete()
                return false
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            onLog("  下载成功：${sizeMb(target)} MB")
            true
        } catch (t: Throwable) {
            onLog("  该源异常：${t.javaClass.simpleName} ${t.message}")
            part.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** 从系统文件选择器拿到的文件复制进私有目录（不需要存储权限） */
    fun importFrom(context: Context, uri: Uri, target: File, onLog: (String) -> Unit): Boolean {
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return false.also { onLog("无法打开所选文件") }

            val part = File(target.parentFile, target.name + ".part")
            input.use { ins ->
                part.outputStream().use { outs ->
                    ins.copyTo(outs, 256 * 1024)
                }
            }
            if (part.length() <= MIN_BYTES) {
                onLog("所选文件只有 ${part.length()} 字节，太小了——大概选错了（要 ggml-*.bin）")
                part.delete()
                return false
            }
            part.renameTo(target)
            onLog("导入成功：${sizeMb(target)} MB → ${target.absolutePath}")
            true
        } catch (t: Throwable) {
            onLog("导入失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
    }
}

package com.dsh.offlineinterp.pipeline

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 翻译接口。
 *
 * 默认策略：**走你自己的 API**（质量最好、手机最省电）。
 * 只有在你明确要求"完全离线"时，才换成本地模型（见文件末尾说明）。
 */
interface Translator {

    val name: String

    fun translate(text: String): String
}

/** 占位实现：原文返回。用于验证管线。 */
class NoopTranslator : Translator {
    override val name: String = "noop"
    override fun translate(text: String): String = text
}

/**
 * OpenAI 兼容接口（POST {baseUrl}/chat/completions）。
 *
 * 适用于：你自己的服务、DeepSeek、Ollama、vLLM、OpenRouter 等一切兼容实现。
 *
 * 用法示例（在 InterpreterService 里替换 NoopTranslator）：
 *   Translator = OpenAiCompatTranslator(
 *       baseUrl = "https://你的域名/v1",
 *       apiKey  = "sk-...",
 *       model   = "你的模型名"
 *   )
 *
 * 实现说明：骨架阶段刻意用 HttpURLConnection（零依赖），JSON 也是手写的最简版，
 * 只解析 chat/completions 的 choices[0].message.content。生产建议换 OkHttp + kotlinx.serialization。
 */
class OpenAiCompatTranslator(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String = "你是实时字幕翻译引擎。把用户输入的文本翻译成简体中文，" +
        "只输出译文，不要解释、不要加引号、不要重复原文。保留人名和专有名词。",
    private val timeoutMs: Int = 8_000
) : Translator {

    override val name: String = "openai-compat:$model"

    override fun translate(text: String): String {
        if (text.isBlank()) return text

        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
        val payload = buildPayload(text)

        var opened: HttpURLConnection? = null
        return try {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            opened = connection

            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let {
                it.bufferedReader(Charsets.UTF_8).use { reader: BufferedReader -> reader.readText() }
            } ?: ""

            if (code !in 200..299) {
                // ★ 把服务器的原话带出来：以前只显示状态码，等于把最有用的信息丢了
                val hint = when (code) {
                    400 -> "请求被拒"
                    401 -> "Key 无效"
                    402 -> "余额不足"
                    403 -> "无权限"
                    404 -> "地址不对（要含 /v1）"
                    429 -> "请求太频繁"
                    else -> ""
                }
                "[HTTP $code $hint] ${extractErrorMessage(body).take(150)}"
            } else {
                extractContent(body) ?: text
            }
        } catch (t: Throwable) {
            "[翻译异常 ${t.javaClass.simpleName}] $text"
        } finally {
            opened?.disconnect()
        }
    }

    private fun buildPayload(text: String): String = buildString {
        append("""{"model":""").append(jsonEscape(model)).append(""",""")
        // 刻意不发 temperature：不同服务商对取值范围要求不一（有的不接受 0），
        // 用服务端默认值最保险，少一个被 400 拒绝的理由。
        append(""""messages":[""")
        append("""{"role":"system","content":""").append(jsonEscape(systemPrompt)).append("""},""")
        append("""{"role":"user","content":""").append(jsonEscape(text)).append("""}""")
        append("""]}""")
    }

    /** 从错误响应里抓出可读原因：优先 error.message，其次整体截断 */
    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return "（服务器没有返回内容）"
        val key = "\"message\""
        val i = body.indexOf(key)
        if (i >= 0) {
            val j = body.indexOf('"', i + key.length)
            if (j >= 0) {
                var k = j + 1
                val sb = StringBuilder()
                while (k < body.length) {
                    val c = body[k]
                    if (c == '\\' && k + 1 < body.length) {
                        when (body[k + 1]) {
                            'n' -> sb.append(' ')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            else -> sb.append(body[k + 1])
                        }
                        k += 2
                        continue
                    }
                    if (c == '"') break
                    sb.append(c)
                    k++
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
        }
        return body.replace("\n", " ")
    }

    /** 最简 JSON 字符串转义 */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 从响应里取 choices[0].message.content（最简实现，不引入 JSON 库） */
    private fun extractContent(body: String): String? {
        val keyIndex = body.indexOf("\"content\"")
        if (keyIndex < 0) return null
        var i = body.indexOf('"', keyIndex + "\"content\"".length)
        // 跳过冒号与空白
        while (i < body.length && body[i] != '"') i++
        if (i >= body.length) return null

        val sb = StringBuilder()
        i++
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> {
                    when (val n = body[i + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'u' -> {
                            if (i + 5 < body.length) {
                                val hex = body.substring(i + 2, i + 6)
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                i += 4
                            }
                        }
                        else -> sb.append(n)
                    }
                    i += 2
                }
                c == '"' -> return sb.toString().trim()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString().trim().ifBlank { null }
    }
}

/*
================================================================================
如果要"完全离线"翻译
================================================================================
思路：把 [Translator] 换成调用本地推理的实现，接口不变。

方案 A：CTranslate2 + OPUS-MT（推荐，体积 75 MB/语向，速度快）
  - 把 sentencepiece + ctranslate2 编成 Android 可用的 .so（社区已有移植尝试，需自己验证）
  - 模型转成 int8 量化，放 models/mt/

方案 B：NLLB-200 distilled 600M（一个模型覆盖多语种，约 600 MB int8）
  - 通过 ONNX Runtime Mobile 或 MNN 加载，配 SentencePiece 分词

方案 C：llama.cpp + 小模型（Qwen2.5-0.5B/1.5B Instruct，Q4 量化）
  - 好处是能顺带做润色与术语统一；坏处是慢，手机上单句可能要 0.5–3 秒

取舍：离线翻译的质量与延迟都明显不如你自己的 API。
     建议做成开关：默认走 API，离线作为备用。
================================================================================
*/

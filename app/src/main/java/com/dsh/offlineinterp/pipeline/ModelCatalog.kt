package com.dsh.offlineinterp.pipeline

import java.net.HttpURLConnection
import java.net.URL

/**
 * 从翻译服务拉取**真实可用的模型列表**（OpenAI 兼容的 `GET {baseUrl}/models`）。
 *
 * 为什么需要它：模型名迭代很快（DeepSeek 从 chat 到 v4.x、各家新模型不断出），
 * 写死在代码里永远追不上。直接从服务端拉，你就能在下拉里选到**服务器当前支持的全部模型**。
 */
object ModelCatalog {

    /** 返回 (模型 id 列表, 错误信息)。失败时列表为空、错误信息非空。 */
    fun fetch(baseUrl: String, apiKey: String, timeoutMs: Int = 8_000): Pair<List<String>, String> {
        if (baseUrl.isBlank()) return emptyList<String>() to "接口地址为空"

        val endpoint = baseUrl.trimEnd('/') + "/models"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                return emptyList<String>() to "HTTP $code：${body.take(160)}"
            }

            val ids = parseIds(body)
            if (ids.isEmpty()) {
                emptyList<String>() to "返回里没解析到模型 id（可能是非标准接口）"
            } else {
                ids to ""
            }
        } catch (t: Throwable) {
            emptyList<String>() to "${t.javaClass.simpleName}：${t.message}"
        } finally {
            conn?.disconnect()
        }
    }

    /** 最简解析：抓所有 "id":"xxx"（OpenAI / DeepSeek / 通义兼容接口都是这个结构） */
    private fun parseIds(body: String): List<String> {
        val out = LinkedHashSet<String>()
        val key = "\"id\""
        var index = body.indexOf(key)
        while (index >= 0) {
            var i = index + key.length
            while (i < body.length && body[i] != '"') i++
            if (i >= body.length) break
            i++
            val start = i
            while (i < body.length && body[i] != '"') i++
            if (i > start) {
                val id = body.substring(start, i).trim()
                if (id.isNotEmpty() && !id.startsWith("model")) out.add(id)
            }
            index = body.indexOf(key, i)
        }
        return out.sorted()
    }
}

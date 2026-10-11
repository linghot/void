package com.dsh.offlineinterp.pipeline

import android.content.Context

/**
 * 按设置决定用哪个翻译引擎。
 *
 * - 设置了「接口地址 + 模型名 + API Key」→ [OpenAiCompatTranslator]（真的翻译）
 * - 没设置 → [NoopTranslator]（原文直通，界面上会明确标注）
 *
 * 兼容一切 OpenAI 风格的 `/chat/completions`：你自己的服务、DeepSeek、Ollama、
 * vLLM、OpenRouter 等。Ollama 的地址通常填 http://手机能访问到的IP:11434/v1
 */
object TranslatorFactory {

    fun create(context: Context): Translator =
        if (Settings.translatorReady(context)) {
            OpenAiCompatTranslator(
                baseUrl = Settings.baseUrl(context),
                apiKey = Settings.apiKey(context),
                model = Settings.mtModel(context),
                systemPrompt = Settings.prompt(context)
            )
        } else {
            NoopTranslator()
        }

    /** 设置页的"测试"用：不落盘，直接拿界面上的值试一次 */
    fun preview(baseUrl: String, apiKey: String, model: String, prompt: String): Translator =
        if (baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()) {
            OpenAiCompatTranslator(baseUrl.trim(), apiKey.trim(), model.trim(), prompt)
        } else {
            NoopTranslator()
        }
}

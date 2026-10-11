package com.dsh.offlineinterp.pipeline

import android.content.Context

/**
 * 应用设置（存在手机本地 SharedPreferences，不上传、不进仓库）。
 *
 * 两类设置：
 *  1. 翻译 API：接口地址 / 模型名 / API Key / 提示词 —— 填了才会真的翻译，
 *     不填就是"原文直通"（NoopTranslator）。
 *  2. 识别：用哪个模型（tiny/base/small）、语言、是否开启"边说边出"临时字幕。
 */
object Settings {

    private const val FILE = "offline_interp_settings"

    private const val KEY_BASE_URL = "mt_base_url"
    private const val KEY_API_KEY = "mt_api_key"
    private const val KEY_MT_MODEL = "mt_model"
    private const val KEY_PROMPT = "mt_prompt"

    private const val KEY_ASR_MODEL = "asr_model_id"
    private const val KEY_ASR_LANG = "asr_language"
    private const val KEY_PARTIAL = "partial_enabled"

    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
    const val DEFAULT_MT_MODEL = "deepseek-chat"
    const val DEFAULT_PROMPT =
        "你是实时字幕翻译引擎。把用户输入的文本翻译成简体中文，只输出译文，" +
            "不要解释、不要加引号、不要重复原文。保留人名和专有名词。"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 翻译 API

    fun baseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL)?.trim().orEmpty()

    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "")?.trim().orEmpty()

    fun mtModel(context: Context): String =
        prefs(context).getString(KEY_MT_MODEL, DEFAULT_MT_MODEL)?.trim().orEmpty()

    fun prompt(context: Context): String =
        prefs(context).getString(KEY_PROMPT, DEFAULT_PROMPT)?.trim().orEmpty()

    /** 三项都填了才算配置好 */
    fun translatorReady(context: Context): Boolean =
        baseUrl(context).isNotBlank() &&
            apiKey(context).isNotBlank() &&
            mtModel(context).isNotBlank()

    fun saveTranslator(
        context: Context,
        baseUrl: String,
        apiKey: String,
        mtModel: String,
        prompt: String
    ) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MT_MODEL, mtModel.trim())
            .putString(KEY_PROMPT, prompt.trim())
            .apply()
    }

    // ---------------------------------------------------------------- 识别

    fun asrModelId(context: Context): String =
        prefs(context).getString(KEY_ASR_MODEL, "base") ?: "base"

    fun asrLanguage(context: Context): String =
        prefs(context).getString(KEY_ASR_LANG, "auto") ?: "auto"

    fun partialEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PARTIAL, true)

    fun saveAsr(context: Context, modelId: String, language: String, partial: Boolean) {
        prefs(context).edit()
            .putString(KEY_ASR_MODEL, modelId)
            .putString(KEY_ASR_LANG, language)
            .putBoolean(KEY_PARTIAL, partial)
            .apply()
    }
}

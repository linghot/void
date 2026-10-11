package com.dsh.offlineinterp.pipeline

import android.content.Context

/**
 * 应用设置（存在手机本地 SharedPreferences，不上传、不进仓库）。
 *
 * 两类设置：
 *  1. 翻译 API：接口地址 / 模型名 / API Key / 提示词 —— 填了才会真的翻译。
 *  2. 识别与外观：模型（tiny/base/small）、语言、线程数、临时字幕开关、
 *     字幕字号与位置。
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
    private const val KEY_THREADS = "asr_threads"

    private const val KEY_OVERLAY_X = "overlay_x"
    private const val KEY_OVERLAY_Y = "overlay_y"
    private const val KEY_OVERLAY_FONT = "overlay_font_sp"
    private const val KEY_OVERLAY_DETAIL = "overlay_detail"

    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
    const val DEFAULT_MT_MODEL = "deepseek-chat"
    const val DEFAULT_PROMPT =
        "你是实时字幕翻译引擎。把用户输入的文本翻译成简体中文，只输出译文，" +
            "不要解释、不要加引号、不要重复原文。保留人名和专有名词。"

    /** 线程数默认 3：4 线程看着快一点，但功耗/发热明显更高 */
    const val DEFAULT_THREADS = 3

    /** 默认 16sp，和手机常见字幕大小接近 */
    const val DEFAULT_FONT_SP = 16

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
        prefs(context).getString(KEY_ASR_MODEL, "tiny") ?: "tiny"

    fun asrLanguage(context: Context): String =
        prefs(context).getString(KEY_ASR_LANG, "auto") ?: "auto"

    /** 默认关闭：这是发热的主要原因，需要更低延迟时再手动打开 */
    fun partialEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PARTIAL, false)

    fun threads(context: Context): Int =
        prefs(context).getInt(KEY_THREADS, DEFAULT_THREADS).coerceIn(1, 8)

    fun saveAsr(
        context: Context,
        modelId: String,
        language: String,
        partial: Boolean,
        threads: Int
    ) {
        prefs(context).edit()
            .putString(KEY_ASR_MODEL, modelId)
            .putString(KEY_ASR_LANG, language)
            .putBoolean(KEY_PARTIAL, partial)
            .putInt(KEY_THREADS, threads.coerceIn(1, 8))
            .apply()
    }

    // ---------------------------------------------------------------- 字幕外观

    fun overlayX(context: Context): Int = prefs(context).getInt(KEY_OVERLAY_X, 24)

    fun overlayY(context: Context): Int = prefs(context).getInt(KEY_OVERLAY_Y, 360)

    fun overlayFontSp(context: Context): Int =
        prefs(context).getInt(KEY_OVERLAY_FONT, DEFAULT_FONT_SP).coerceIn(10, 30)

    fun overlayDetail(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_DETAIL, true)

    fun saveOverlayPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit()
            .putInt(KEY_OVERLAY_X, x)
            .putInt(KEY_OVERLAY_Y, y)
            .apply()
    }

    fun saveOverlayStyle(context: Context, fontSp: Int, detail: Boolean) {
        prefs(context).edit()
            .putInt(KEY_OVERLAY_FONT, fontSp.coerceIn(10, 30))
            .putBoolean(KEY_OVERLAY_DETAIL, detail)
            .apply()
    }

    fun toggleOverlayDetail(context: Context): Boolean {
        val next = !overlayDetail(context)
        prefs(context).edit().putBoolean(KEY_OVERLAY_DETAIL, next).apply()
        return next
    }

    /** 位置重置成默认值（拖飞了/拖到屏幕外了用） */
    fun resetOverlayPosition(context: Context) {
        prefs(context).edit()
            .putInt(KEY_OVERLAY_X, 24)
            .putInt(KEY_OVERLAY_Y, 360)
            .apply()
    }
}

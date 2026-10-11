package com.dsh.offlineinterp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.dsh.offlineinterp.pipeline.ModelStore
import com.dsh.offlineinterp.pipeline.Settings
import com.dsh.offlineinterp.pipeline.TranslatorFactory

/**
 * 设置页（v0.7 重做）：翻译 API / 识别 / 字幕外观三块卡片。
 *
 * **API Key 只留在你的手机里**：不写进代码、不进仓库。
 */
class SettingsActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var etBaseUrl: EditText
    private lateinit var etModel: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etPrompt: EditText
    private lateinit var spAsrModel: Spinner
    private lateinit var spAsrLang: Spinner
    private lateinit var spThreads: Spinner
    private lateinit var spFont: Spinner
    private lateinit var cbPartial: CheckBox
    private lateinit var tvResult: TextView

    private val langIds = listOf("auto", "zh", "en", "ja")
    private val langLabels = listOf("自动检测", "中文（zh）", "英文（en）", "日文（ja）")

    private val threadValues = listOf(2, 3, 4, 6)
    private val threadLabels = listOf("2 线程 · 最省电", "3 线程 · 推荐", "4 线程 · 更快更热", "6 线程 · 最快最热")

    private val fontValues = listOf(12, 14, 16, 18, 20, 24)
    private val fontLabels = listOf("12sp 很小", "14sp 偏小", "16sp 标准", "18sp 偏大", "20sp 大", "24sp 很大")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etBaseUrl = findViewById(R.id.etBaseUrl)
        etModel = findViewById(R.id.etModel)
        etApiKey = findViewById(R.id.etApiKey)
        etPrompt = findViewById(R.id.etPrompt)
        spAsrModel = findViewById(R.id.spAsrModel)
        spAsrLang = findViewById(R.id.spAsrLang)
        spThreads = findViewById(R.id.spThreads)
        spFont = findViewById(R.id.spFont)
        cbPartial = findViewById(R.id.cbPartial)
        tvResult = findViewById(R.id.tvTestResult)

        etBaseUrl.setText(Settings.baseUrl(this))
        etModel.setText(Settings.mtModel(this))
        etApiKey.setText(Settings.apiKey(this))
        etPrompt.setText(Settings.prompt(this))

        spAsrModel.adapter = simple(ModelStore.MODELS.map { it.label })
        spAsrModel.setSelection(
            ModelStore.MODELS.indexOfFirst { it.id == Settings.asrModelId(this) }
                .coerceIn(0, ModelStore.MODELS.size - 1)
        )

        spAsrLang.adapter = simple(langLabels)
        spAsrLang.setSelection(langIds.indexOf(Settings.asrLanguage(this)).coerceIn(0, langIds.size - 1))

        spThreads.adapter = simple(threadLabels)
        spThreads.setSelection(threadValues.indexOf(Settings.threads(this)).let { if (it < 0) 1 else it })

        spFont.adapter = simple(fontLabels)
        spFont.setSelection(fontValues.indexOf(Settings.overlayFontSp(this)).let { if (it < 0) 2 else it })

        cbPartial.isChecked = Settings.partialEnabled(this)
        cbPartial.setOnCheckedChangeListener { _, checked ->
            if (checked) tvResult.text = "临时字幕会明显增加耗电与发热；设备变热时 App 会自动关掉它。"
        }

        tvResult.text = if (Settings.translatorReady(this)) {
            "翻译：已配置 ✅（字幕第二行会是中文）"
        } else {
            "翻译：未配置 —— 当前第二行会与第一行相同"
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener { save() }
        findViewById<Button>(R.id.btnTestMt).setOnClickListener { test() }
        findViewById<Button>(R.id.btnResetOverlay).setOnClickListener {
            Settings.resetOverlayPosition(this)
            Toast.makeText(this, "字幕位置已重置（下次启动生效）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun simple(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun selectedModelId(): String =
        ModelStore.MODELS[spAsrModel.selectedItemPosition.coerceIn(0, ModelStore.MODELS.size - 1)].id

    private fun selectedLang(): String =
        langIds[spAsrLang.selectedItemPosition.coerceIn(0, langIds.size - 1)]

    private fun selectedThreads(): Int =
        threadValues[spThreads.selectedItemPosition.coerceIn(0, threadValues.size - 1)]

    private fun selectedFont(): Int =
        fontValues[spFont.selectedItemPosition.coerceIn(0, fontValues.size - 1)]

    private fun save() {
        Settings.saveTranslator(
            this,
            etBaseUrl.text.toString(),
            etApiKey.text.toString(),
            etModel.text.toString(),
            etPrompt.text.toString()
        )
        Settings.saveAsr(this, selectedModelId(), selectedLang(), cbPartial.isChecked, selectedThreads())
        Settings.saveOverlayStyle(this, selectedFont(), Settings.overlayDetail(this))

        Toast.makeText(this, "已保存（仅存在本机）", Toast.LENGTH_SHORT).show()
        tvResult.text = buildString {
            append(if (Settings.translatorReady(this@SettingsActivity)) "翻译：已配置 ✅" else "翻译：未配置（原文直通）")
            append("\n识别模型：").append(selectedModelId())
            append("　语言：").append(selectedLang())
            append("　线程：").append(selectedThreads())
            append("\n临时字幕：").append(if (cbPartial.isChecked) "开" else "关")
            append("　字幕字号：").append(selectedFont()).append("sp")
        }
    }

    private fun test() {
        val baseUrl = etBaseUrl.text.toString()
        val apiKey = etApiKey.text.toString()
        val model = etModel.text.toString()
        val prompt = etPrompt.text.toString()

        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            tvResult.text = "测试失败：接口地址 / 模型名 / API Key 三项都要填"
            return
        }

        tvResult.text = "正在测试…（最多等 8 秒）"
        Thread({
            val translator = TranslatorFactory.preview(baseUrl, apiKey, model, prompt)
            val started = System.currentTimeMillis()
            val out = translator.translate("Hello, this is a subtitle translation test.")
            val cost = System.currentTimeMillis() - started
            ui.post { tvResult.text = "引擎：${translator.name}\n耗时：${cost}ms\n返回：$out" }
        }, "mt-test").start()
    }
}

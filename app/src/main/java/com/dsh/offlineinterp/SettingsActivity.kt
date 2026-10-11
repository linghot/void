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
 * 设置页：翻译 API + 识别参数。全部存在手机本地（SharedPreferences）。
 *
 * **API Key 只留在你的手机里**：不写进代码、不进仓库、我只提供输入框。
 */
class SettingsActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var etBaseUrl: EditText
    private lateinit var etModel: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etPrompt: EditText
    private lateinit var spAsrModel: Spinner
    private lateinit var spAsrLang: Spinner
    private lateinit var cbPartial: CheckBox
    private lateinit var tvResult: TextView

    private val langIds = listOf("auto", "zh", "en", "ja")
    private val langLabels = listOf("自动检测", "中文", "英文", "日文")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etBaseUrl = findViewById(R.id.etBaseUrl)
        etModel = findViewById(R.id.etModel)
        etApiKey = findViewById(R.id.etApiKey)
        etPrompt = findViewById(R.id.etPrompt)
        spAsrModel = findViewById(R.id.spAsrModel)
        spAsrLang = findViewById(R.id.spAsrLang)
        cbPartial = findViewById(R.id.cbPartial)
        tvResult = findViewById(R.id.tvTestResult)

        // 回填已保存的值
        etBaseUrl.setText(Settings.baseUrl(this))
        etModel.setText(Settings.mtModel(this))
        etApiKey.setText(Settings.apiKey(this))
        etPrompt.setText(Settings.prompt(this))

        spAsrModel.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ModelStore.MODELS.map { it.label }
        )
        spAsrModel.setSelection(
            ModelStore.MODELS.indexOfFirst { it.id == Settings.asrModelId(this) }
                .coerceIn(0, ModelStore.MODELS.size - 1)
        )

        spAsrLang.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            langLabels
        )
        spAsrLang.setSelection(
            langIds.indexOf(Settings.asrLanguage(this)).coerceIn(0, langIds.size - 1)
        )

        cbPartial.isChecked = Settings.partialEnabled(this)

        tvResult.text = if (Settings.translatorReady(this)) {
            "翻译：已配置 ✅（字幕第二行会是中文）"
        } else {
            "翻译：未配置 —— 当前是「原文直通」，第二行会重复第一行"
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener { save() }
        findViewById<Button>(R.id.btnTestMt).setOnClickListener { test() }
    }

    private fun selectedModelId(): String =
        ModelStore.MODELS[spAsrModel.selectedItemPosition.coerceIn(0, ModelStore.MODELS.size - 1)].id

    private fun selectedLang(): String =
        langIds[spAsrLang.selectedItemPosition.coerceIn(0, langIds.size - 1)]

    private fun save() {
        Settings.saveTranslator(
            this,
            etBaseUrl.text.toString(),
            etApiKey.text.toString(),
            etModel.text.toString(),
            etPrompt.text.toString()
        )
        Settings.saveAsr(this, selectedModelId(), selectedLang(), cbPartial.isChecked)

        Toast.makeText(this, "已保存（仅存在本机）", Toast.LENGTH_SHORT).show()
        tvResult.text = buildString {
            append(if (Settings.translatorReady(this@SettingsActivity)) "翻译：已配置 ✅" else "翻译：未配置（原文直通）")
            append("\n识别模型：").append(selectedModelId())
            append("　语言：").append(selectedLang())
            append("　临时字幕：").append(if (cbPartial.isChecked) "开" else "关")
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
            ui.post {
                tvResult.text = "引擎：${translator.name}\n耗时：${cost}ms\n返回：$out"
            }
        }, "mt-test").start()
    }
}

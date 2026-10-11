package com.dsh.offlineinterp

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.dsh.offlineinterp.pipeline.ModelCatalog
import com.dsh.offlineinterp.pipeline.ModelStore
import com.dsh.offlineinterp.pipeline.Settings
import com.dsh.offlineinterp.pipeline.TranslatorFactory
import com.dsh.offlineinterp.pipeline.Updater

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
    private lateinit var spProvider: Spinner
    private lateinit var spMtModel: Spinner
    private lateinit var spAsrModel: Spinner
    private lateinit var spAsrLang: Spinner
    private lateinit var spThreads: Spinner
    private lateinit var spFont: Spinner
    private lateinit var cbPartial: CheckBox
    private lateinit var tvResult: TextView
    private lateinit var etUpdateUrl: EditText
    private lateinit var etUpdateToken: EditText
    private lateinit var tvUpdateResult: TextView

    private val langIds = listOf("auto", "zh", "en", "ja")
    private val langLabels = listOf("自动检测", "中文（zh）", "英文（en）", "日文（ja）")

    private val threadValues = listOf(2, 3, 4, 6)
    private val threadLabels = listOf("2 线程 · 最省电", "3 线程 · 推荐", "4 线程 · 更快更热", "6 线程 · 最快最热")

    private val fontValues = listOf(12, 14, 16, 18, 20, 24)
    private val fontLabels = listOf("12sp 很小", "14sp 偏小", "16sp 标准", "18sp 偏大", "20sp 大", "24sp 很大")

    /** 服务商预设：解决"不知道模型名该填什么" */
    private data class Provider(val label: String, val baseUrl: String, val models: List<String>)

    private val providers = listOf(
        Provider("自定义 / 自己填地址", "", emptyList()),
        Provider(
            "DeepSeek 官方（推荐）",
            "https://api.deepseek.com/v1",
            listOf("deepseek-chat", "deepseek-reasoner")
        ),
        Provider(
            "OpenAI",
            "https://api.openai.com/v1",
            listOf("gpt-4o-mini", "gpt-4o")
        ),
        Provider(
            "阿里云百炼（通义千问）",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            listOf("qwen-plus", "qwen-turbo", "qwen-max")
        ),
        Provider(
            "智谱 GLM",
            "https://open.bigmodel.cn/api/paas/v4",
            listOf("glm-4-flash", "glm-4-plus")
        ),
        Provider(
            "OpenRouter",
            "https://openrouter.ai/api/v1",
            listOf("openai/gpt-4o-mini", "anthropic/claude-3.5-haiku")
        ),
        Provider(
            "本机 Ollama（电脑上跑的）",
            "http://192.168.1.100:11434/v1",
            listOf("qwen2.5:7b", "llama3.1:8b")
        )
    )

    /** 正在程序化设置下拉，避免回调把已保存的值覆盖掉 */
    private var binding = false

    /** 下拉里的「手动输入」项：任何没列出来的模型名都能这样填 */
    private val MANUAL_ENTRY = "✏️ 手动输入模型名"

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

        // 服务商 / 模型下拉
        spProvider = findViewById(R.id.spProvider)
        spMtModel = findViewById(R.id.spMtModel)
        spProvider.adapter = simple(providers.map { it.label })
        val matched = providers.indexOfFirst {
            it.baseUrl.isNotBlank() && Settings.baseUrl(this).startsWith(it.baseUrl)
        }
        val startIndex = if (matched < 0) 0 else matched
        spProvider.setSelection(startIndex)
        bindProvider(startIndex, fillFields = false)

        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (binding) return
                bindProvider(position, fillFields = true)
                autoFetchModelsIfPossible()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // API Key：用 textNoSuggestions 避开系统的"安全键盘"（它会禁止粘贴）
        etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        findViewById<Button>(R.id.btnPasteApiKey).setOnClickListener { pasteInto(etApiKey) }
        findViewById<CheckBox>(R.id.cbHideKey).setOnCheckedChangeListener { _, checked ->
            etApiKey.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            etApiKey.setSelection(etApiKey.text.length)
        }

        // 应用内更新
        etUpdateUrl = findViewById(R.id.etUpdateUrl)
        etUpdateToken = findViewById(R.id.etUpdateToken)
        tvUpdateResult = findViewById(R.id.tvUpdateResult)
        etUpdateUrl.setText(Settings.updateUrl(this))
        etUpdateToken.setText(Settings.updateToken(this))
        findViewById<Button>(R.id.btnPasteToken).setOnClickListener { pasteInto(etUpdateToken) }
        findViewById<Button>(R.id.btnFetchModels).setOnClickListener { fetchModels() }
        findViewById<Button>(R.id.btnTestModels).setOnClickListener { testModels() }
        findViewById<Button>(R.id.btnPasteModel).setOnClickListener { pasteInto(etModel) }
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkUpdate() }
        tvUpdateResult.text = "当前版本：${Updater.currentVersion(this)}"

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

    /**
     * 选服务商 → 自动填接口地址与候选模型。
     *
     * 候选 = 内置常用（可能过时） + **上次从服务器拉到的真实列表** + 「✏️ 手动输入」。
     * 这样服务商出多少新模型都不怕：要么能选到，要么能手打。
     */
    private fun bindProvider(position: Int, fillFields: Boolean) {
        val provider = providers[position.coerceIn(0, providers.size - 1)]

        val models = (provider.models + Settings.cachedModels(this)).distinct()
        val items = listOf(MANUAL_ENTRY) + models

        binding = true
        spMtModel.adapter = simple(items)
        if (fillFields && provider.baseUrl.isNotBlank()) {
            etBaseUrl.setText(provider.baseUrl)
        }
        if (fillFields) {
            models.firstOrNull()?.let { etModel.setText(it) }
        }
        // 下拉停在当前模型名那一项；没有对应项就停在「手动输入」
        val current = etModel.text.toString().trim()
        val idx = items.indexOf(current)
        spMtModel.setSelection(if (idx < 0) 0 else idx)
        binding = false

        spMtModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (binding) return
                val value = parent?.getItemAtPosition(pos)?.toString() ?: return
                if (value == MANUAL_ENTRY) {
                    etModel.setText("")
                    etModel.requestFocus()
                    tvResult.text = "请手动输入模型名（用下面的粘贴按钮更快）"
                } else {
                    etModel.setText(value)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** 直接读剪贴板写入输入框：安全键盘禁止长按粘贴时用这个 */
    private fun pasteInto(target: EditText) {
        val text = try {
            val manager = getSystemService(ClipboardManager::class.java)
            val clip = manager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this).toString().trim()
            } else {
                ""
            }
        } catch (t: Throwable) {
            ""
        }

        if (text.isEmpty()) {
            Toast.makeText(this, "剪贴板是空的：先在别处复制 Token，再点这个按钮", Toast.LENGTH_LONG).show()
            return
        }
        target.setText(text)
        target.setSelection(text.length)
        Toast.makeText(this, "已粘贴（${text.length} 个字符）", Toast.LENGTH_SHORT).show()
    }

    /** 只用于诊断：不泄露完整 Token */
    private fun describeToken(token: String): String = when {
        token.isBlank() -> "未填"
        token.length < 20 -> "异常（只有 ${token.length} 个字符，可能没粘贴完整）"
        else -> "已填 ${token.length} 字符（${token.take(11)}…${token.takeLast(4)}）"
    }

    /** 从服务端拉真实模型列表：模型名迭代很快，写死在代码里永远追不上 */
    private fun fetchModels() {
        val baseUrl = etBaseUrl.text.toString()
        val apiKey = etApiKey.text.toString()
        if (baseUrl.isBlank()) {
            tvResult.text = "先在上面填接口地址，再拉模型列表"
            return
        }
        tvResult.text = "正在拉取模型列表…"
        Thread({ fetchAndMerge(baseUrl, apiKey, quiet = false) }, "catalog").start()
    }

    /** 选了服务商就顺手拉一次（有 Key 才拉，安静失败不打扰） */
    private fun autoFetchModelsIfPossible() {
        val baseUrl = etBaseUrl.text.toString()
        val apiKey = etApiKey.text.toString()
        if (baseUrl.isBlank() || apiKey.isBlank()) return
        Thread({ fetchAndMerge(baseUrl, apiKey, quiet = true) }, "catalog-auto").start()
    }

    private fun fetchAndMerge(baseUrl: String, apiKey: String, quiet: Boolean) {
        val (ids, err) = ModelCatalog.fetch(baseUrl, apiKey)
        if (ids.isEmpty()) {
            ui.post { if (!quiet) tvResult.text = "拉取失败：$err（可以继续手打模型名）" }
            return
        }
        Settings.saveCachedModels(this, ids)
        ui.post {
            val provider = providers[spProvider.selectedItemPosition.coerceIn(0, providers.size - 1)]
            val models = (provider.models + ids).distinct()
            binding = true
            spMtModel.adapter = simple(listOf(MANUAL_ENTRY) + models)
            val current = etModel.text.toString().trim()
            val index = models.indexOf(current)
            spMtModel.setSelection(if (index < 0) 0 else index + 1)
            binding = false
            tvResult.text = "服务器返回 ${ids.size} 个模型：" +
                ids.take(10).joinToString("、") + if (ids.size > 10) " …" else ""
        }
    }

    /**
     * 逐个真发一次最小请求，告诉你**哪些模型名真的能用**。
     * 这是对"模型选不到/选了报错"最权威的答案，测完自动选中第一个可用的。
     */
    private fun testModels() {
        val baseUrl = etBaseUrl.text.toString()
        val apiKey = etApiKey.text.toString()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            tvResult.text = "先填接口地址和 API Key，再测模型"
            return
        }

        val candidates = (0 until spMtModel.adapter.count)
            .map { spMtModel.adapter.getItem(it).toString() }
            .filter { it != MANUAL_ENTRY }
            .ifEmpty { listOf("deepseek-chat", "deepseek-reasoner") }

        tvResult.text = "开始逐个测试 ${candidates.size} 个模型…"
        Thread({
            val report = StringBuilder()
            var firstOk: String? = null
            candidates.forEachIndexed { index, model ->
                val result = ModelCatalog.probe(baseUrl, apiKey, model)
                if (result.ok && firstOk == null) firstOk = model
                report.append(model).append(" → ").append(result.message).append('\n')
                ui.post { tvResult.text = "测试中 ${index + 1}/${candidates.size}…\n$report" }
            }
            val ok = firstOk
            ui.post {
                tvResult.text = buildString {
                    append(report)
                    if (ok != null) {
                        append("\n已自动选中可用模型：").append(ok)
                        etModel.setText(ok)
                    } else {
                        append("\n没有可用模型：检查 Key、余额，或先点「从服务器拉取模型列表」拿到准确名字")
                    }
                }
            }
        }, "probe-models").start()
    }

    /** 应用内更新：查 Release → 下载 APK → 交给系统安装器 */
    private fun checkUpdate() {
        val url = etUpdateUrl.text.toString().ifBlank { Settings.DEFAULT_UPDATE_URL }
        val token = etUpdateToken.text.toString()
        Settings.saveUpdater(this, url, token)

        val current = Updater.currentVersion(this)
        tvUpdateResult.text = "当前 $current ｜ Token ${describeToken(token)}\n正在检查…"
        Thread({
            val (info, err) = Updater.check(url, token)
            if (info == null) {
                ui.post { tvUpdateResult.text = "检查失败：$err" }
                return@Thread
            }
            if (info.version == current) {
                ui.post { tvUpdateResult.text = "已是最新版本（${info.tag}）" }
                return@Thread
            }
            ui.post { tvUpdateResult.text = "发现新版本 ${info.tag}，开始下载…" }
            val (ok, msg) = Updater.downloadAndInstall(
                this,
                info.apkUrl,
                "offline-interp-${info.tag}.apk",
                token
            ) { done, total ->
                val pct = if (total > 0) done * 100 / total else -1
                ui.post { tvUpdateResult.text = "下载中 $pct%（${done / 1024 / 1024} MB）" }
            }
            ui.post { tvUpdateResult.text = if (ok) msg else "更新失败：$msg" }
        }, "update").start()
    }

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
        Settings.saveUpdater(this, etUpdateUrl.text.toString(), etUpdateToken.text.toString())

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

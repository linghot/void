package com.dsh.offlineinterp.pipeline

/**
 * 语音识别接口。输入 16 kHz 单声道 PCM16，输出文本。
 *
 * 现在只有 [StubAsrEngine]，是为了让整条管线（切句 → 翻译 → 字幕）先跑起来。
 * 真模型接入见下面的接入说明。
 */
interface AsrEngine {

    val name: String

    /** 加载模型；返回 false 表示不可用，管线应降级而不是崩 */
    fun prepare(): Boolean

    /** 识别一段音频（同步阻塞，调用方负责放在工作线程） */
    fun transcribe(pcm16k: ShortArray): String

    /**
     * 短窗口"临时识别"（边说边出用）。默认等同 [transcribe]；
     * 流式引擎实现它可以让临时结果走更轻量/更保守的解码路径。
     */
    fun transcribePartial(pcm16k: ShortArray): String = transcribe(pcm16k)

    fun release() {}

    /** 这段音频对应多少毫秒 */
    fun durationMs(pcm16k: ShortArray): Int = pcm16k.size * 1000 / 16_000
}

/**
 * 占位实现：不识别，只回一段标记文本，用于验证管线与字幕 UI。
 * 看到 "[ASR未接入 …ms]" 说明采集、切句、翻译、字幕这条链路是通的。
 */
class StubAsrEngine : AsrEngine {
    override val name: String = "stub"

    override fun prepare(): Boolean = true

    override fun transcribe(pcm16k: ShortArray): String =
        "[ASR未接入 ${durationMs(pcm16k)}ms]"
}

/*
================================================================================
接入说明（选一条走，接口不用改）
================================================================================

方案 A：whisper.cpp（先跑通，最省事）
  1. 把 whisper.cpp 以 CMake 方式编成 .so，或在 app/build.gradle.kts 里加
     externalNativeBuild 指向其 CMakeLists
  2. 用 JNI 暴露三个方法：init(modelPath)、transcribe(floatArray)→String、free()
  3. 实现 WhisperAsrEngine：
       prepare()  → 调 native init，模型放 getExternalFilesDir(null)/models/asr/
       transcribe() → PCM16 转 float(-1..1) 后交给 native
  4. 注意：whisper 不是流式模型，必须靠 Segmenter 切好句子再整段送进去

方案 B：sherpa-onnx（推荐，可流式，延迟更低）
  1. 引入 sherpa-onnx 的 AAR（自带 .so），或自己编 JNI
  2. 用 OnlineRecognizer 做流式解码：每个音频块调 acceptWaveform，
     再调 decode + getResult 取增量文本
  3. 这时 Segmenter 的角色从"切整句"变成"只在长停顿时做句子边界"，
     识别结果可以边出边翻，端到端延迟会明显好于方案 A
  4. 中文建议选 zipformer / paraformer 的中文预训练模型

方案 C：Vosk（最轻，效果一般）
  1. 加 com.alphacephei:vosk-android 依赖（需联网拉取）
  2. 用 Recognizer + SpeechService，同样是流式

不管哪条路：模型文件都要放到 App 能读到的地方，
建议 getExternalFilesDir(null)/models/asr/，与项目里的 models/ 目录约定一致。
================================================================================
*/

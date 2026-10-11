# 安卓离线同传（手机内播放音频 → 实时中文字幕）

目标：把**手机里正在播放的视频/直播的声音**抓下来，在本地（离线）做语音识别 + 翻译，
用悬浮字幕叠在画面上。翻译可以走自建 API（可切换）。

> 现状：**M0 已通过真机实测** —— 目标 App 的播放音频判定为 CAPTURABLE
> （RMS 12204.5、非静音占比 98%），悬浮字幕链路也已跑通。
> 当前版本 **v0.2-m2**：已接入 whisper.cpp 离线识别，只差重新构建 + 下载模型。
> 但我**仍未编译验证过**（本机没有 JDK/NDK/SDK），见文末「诚实声明」。

---

## 1. 先看这一节：本方案最大的风险不在识别，在"能不能抓到声音"

要翻手机内播放的音频，安卓唯一正统途径是 **AudioPlaybackCapture**（API 29 / Android 10+），
它必须和 **MediaProjection（录屏授权）** 配合使用。但音频是否真的能被抓到，
**取决于正在播放的那个 App 自己怎么声明**：

| 情况 | 能否抓到 | 原因 |
|---|---|---|
| 普通视频/音乐 App，未声明禁止 | ✅ 能 | 允许被捕获 |
| 目标 App 在清单里声明 `android:allowAudioPlaybackCapture="false"` | ❌ 不能 | 系统直接不给 |
| 受 DRM 保护的内容（部分流媒体、付费直播） | ❌ 不能 | 内容被标记为受保护 |
| 通话类音频（`USAGE_VOICE_COMMUNICATION`） | ❌ 不能 | 系统禁止捕获通话 |
| 音频 usage 与我们的匹配规则不一致 | ⚠️ 看配置 | 需要在 `addMatchingUsage` 里列全 |

**结论：这件事必须先实测，不能先写识别。** 所以本项目的 M0 不是"接模型"，
而是一个 **捕获探针**：抓 10 秒目标 App 的音频，统计音量，
如果全程接近 0 而目标 App 明明在出声 → 判定"该 App 禁止捕获"，方案必须改道
（改用麦克风听外放，或改用 OCR 屏幕翻译）。详见 `docs/音频捕获限制.md`。

---

## 2. 里程碑

| 阶段 | 内容 | 验收标准 | 状态 |
|---|---|---|---|
| **M0** | 捕获探针：MediaProjection + AudioPlaybackCapture 抓 10 秒，输出音量统计 | 明显非零音量 | ✅ **已通过实测**（RMS 12204.5、非静音 98%、CAPTURABLE） |
| M1 | 字幕管线打通：切句 → 假识别 → 假翻译 → 悬浮字幕 | 边播边出字幕 | ✅ 已跑通（悬浮字幕已出现在真机上） |
| **M2** | 接入离线 ASR：whisper.cpp + JNI + CMake | 出真中文，端到端 1.5~3 s | 🚧 代码已完成（v0.2-m2），**待你重新构建 + 下载模型** |
| M3 | 换 sherpa-onnx 流式识别，延迟压到 1 秒级 | 边说边出字 | 接入路线已核实，见 `docs/M2-接入识别模型.md` |
| M4 | 接翻译（你的 API 或本地 MT）、TTS 朗读、悬浮窗样式 | 日常可用 | 翻译侧 HTTP 实现已写好，待启用 |
| M4 | 打磨：悬浮窗样式、TTS 朗读、蓝牙耳机双人模式 | 日常可用 | 未开始 |

**M0 不通过就不要投入 M2**——那是白干。

---

## 3. 目录结构

```
安卓离线同传/
├─ README.md                      本文件：目标、风险、里程碑、诚实声明
├─ docs/
│   ├─ 音频捕获限制.md            AudioPlaybackCapture 的规则、失败矩阵、验证方法
│   └─ 模型选型与性能预算.md      ASR / MT / TTS 的模型体积、内存、延迟预估
├─ models/README.md              模型放哪、去哪拿（含体积对照）
└─ app/src/main/
    ├─ AndroidManifest.xml       权限与前台服务声明
    └─ java/com/dsh/offlineinterp/
        ├─ MainActivity.kt                     入口：申请权限、启动探针/服务
        ├─ capture/AudioCaptureProbe.kt        ★ M0：能不能抓到声音
        ├─ capture/AppAudioCapture.kt          抓 PCM（48k 单声道）+ 降采样到 16k
        ├─ pipeline/Segmenter.kt               能量 VAD 切句（后续可换 Silero VAD）
        ├─ pipeline/AsrEngine.kt               识别接口（含 Stub 与接入说明）
        ├─ pipeline/Translator.kt              翻译接口（含 OpenAI 兼容 HTTP 实现）
        ├─ pipeline/SubtitleOverlay.kt         悬浮字幕窗
        └─ service/InterpreterService.kt       前台服务，把管线串起来
```

---

## 4. 构建前提

- JDK 17、Android SDK Platform 34、Gradle 8.x（AGP 8.x）
- **minSdk 29**：AudioPlaybackCapture 从 Android 10 才有，低版本无解
- 无第三方依赖：骨架刻意只用平台 API（`HttpURLConnection`、平台 `Activity`、平台 `Notification`），
  以便在没有网络的环境里也能构建
- 接 ASR 时再加原生库：sherpa-onnx 的 AAR（含 `.so`）或 whisper.cpp 的 CMake 工程

构建命令（待你在有 SDK 的机器上执行）：

```bash
cd 安卓离线同传
./gradlew :app:assembleDebug
```

---

## 5. 首次上机怎么用

1. 装好后打开 App → 点「授予悬浮窗权限」→ 点「申请录音权限」
2. **先在自己熟悉的视频 App 里播放声音**，点「开始捕获探针」
3. 系统弹出录屏授权 → 允许（会显示"正在投屏"提示，这是系统要求，正常）
4. 看 10 秒后的统计：
   - `峰值 / 均值` 明显 > 0 → ✅ 这条路走得通，进入 M2
   - 全程 ≈ 0 而视频在响 → ❌ 该 App 禁止捕获，换 App 试或改走麦克风/OCR
5. 把探针结果告诉我，我据此决定接哪套识别方案

---

## 6. 诚实声明：我能做到哪一步

| 项目 | 我的把握 | 说明 |
|---|---|---|
| 架构与源码 | 高 | 组件都是成熟开源方案，不是猜的 |
| 编译出 APK 并上机跑通 | **低** | 本环境没有 Android SDK/NDK、没有真机、也没有网络下载模型，**我无法编译验证** |
| 抓"手机内播放音频" | **中** | 取决于目标 App 是否允许捕获，这是系统规则，代码绕不过 |
| 端到端延迟 < 1 秒 | 低 | 手机算力上限 |
| 端到端延迟 1–3 秒 | 中高 | 现实可达区间 |

也就是说：**代码我可以继续写，但"能不能用"必须靠你在真机上跑 M0 来定。**
在 M0 结果出来之前，我不会假装后面几步已经稳了。

---

## 7. 相关背景资料

同目录平级的 `安卓实时翻译App调研.txt` 记录了选型调研：
现成 App（沉浸式翻译）能覆盖"浏览器里的网页视频/直播"，但盖不到原生 App；
本目录的方案就是为了补上这一块。

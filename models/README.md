# models/ 目录说明

本目录**现在是空的**，模型文件需要你自己获取后放进来（或运行时下载到 App 私有目录）。

## 为什么我不帮你下载

1. 体积大（单个模型 40 MB – 1 GB 级），本会话的抓取通道只能取文本，取不了二进制文件；
2. 模型许可各不相同，需要你按用途确认；
3. 放在项目里会让仓库变得很重——更推荐运行时下载到 App 私有目录。

## 放什么（按接入顺序）

| 用途 | 建议文件 | 体积量级 | 来源关键词 |
|---|---|---|---|
| ASR（先用） | whisper.cpp 的 `ggml-tiny.bin` / `ggml-base.bin` | 40–80 MB | whisper.cpp releases |
| ASR（后换） | sherpa-onnx 流式中文 zipformer/paraformer | 50–300 MB | sherpa-onnx 预训练模型页 |
| VAD | Silero VAD ONNX | ~2 MB | silero-vad |
| MT（可选，离线） | OPUS-MT 中英 / NLLB-200 distilled | 75 MB – 600 MB | CTranslate2 / Helsinki-NLP |

## App 应该去哪里找模型

按优先级：

1. `context.getExternalFilesDir(null)/models/`（免权限，卸载即清）
2. `context.filesDir/models/`
3. 打包进 `assets/`（仅适合小模型，会增加 APK 体积）

## 命名约定（本项目内部）

```
models/
├─ asr/           识别模型
├─ vad/           端点检测
└─ mt/            离线翻译（不用则留空）
```

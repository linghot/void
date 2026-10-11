# 翻译 API 设置说明

App 里点 **⚙ 设置**，填三项就能让字幕第二行变成中文。全部存在**你手机本地**
（SharedPreferences），不写进代码、不进 GitHub。

## 1. 三个字段怎么填

| 字段 | 说明 | 例子 |
|---|---|---|
| 接口地址 | OpenAI 兼容的 base url，**要带 /v1** | `https://api.deepseek.com/v1` |
| 模型名 | 服务商给的模型 id | `deepseek-chat` |
| API Key | 你的密钥（只存本机） | `sk-xxxxxxxx` |

第四项**翻译提示词**可以不动，默认已经写好（只输出译文、保留专有名词）。

填完点 **保存**；想立刻知道通不通，点 **测试连接**——它会把
"Hello, this is a subtitle translation test." 发过去，直接显示返回结果和耗时。

## 2. 常见服务商怎么填

| 服务 | 接口地址 | 模型名例子 |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| 阿里云百炼（通义） | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| OpenRouter | `https://openrouter.ai/api/v1` | `openai/gpt-4o-mini` |
| 你自己搭的（vLLM / one-api / new-api） | `http://你的地址/v1` | 你的模型名 |
| 本机 Ollama（**手机要能访问到电脑**） | `http://192.168.x.x:11434/v1` | `qwen2.5:7b` |

> 手机连电脑上的 Ollama 时，记得让 Ollama 监听 0.0.0.0，并放行防火墙端口 11434。

## 3. 为什么默认是"原文直通"

不填 Key 时用的是 `NoopTranslator`：**原样返回识别结果**。
这是故意的——这样你能先确认"识别"这一段是好的，再叠加翻译，
出问题时一眼就知道是识别还是翻译的锅。主界面日志里会写明当前用的是哪个翻译引擎。

## 4. 成本与延迟提示

- 字幕是逐句调用，**按 token 计费**，看一小时视频的量不小；建议用便宜模型
  （`deepseek-chat`、`glm-4-flash`、`gpt-4o-mini`）。
- 每次调用的往返延迟会直接叠加到字幕上。实测慢的接口会让字幕明显滞后，
  这种情况可以在提示词里要求"只输出译文"，速度会快一些。
- 失败时会自动降级：HTTP 报错或超时（8 秒）时把原文交回去，字幕不会断。

## 5. 想改成完全离线翻译

本地 MT（OPUS-MT / NLLB 量化）体积 75 MB~600 MB，手机上明显更慢。
如果你要求"不许联网也能翻"，告诉我，我把 `TranslatorFactory` 换成
CTranslate2/ONNX 的本地实现（接口不变，只换实现类）。

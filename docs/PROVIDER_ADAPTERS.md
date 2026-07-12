# Provider 适配层设计

## 参考插件结论

`/data/data/com.termux/files/home/astrbot_plugin_selfie_image` 已经实现了可迁移的生图 provider 架构：

- `providers.py`
  - `BaseImageAdapter`
  - `OpenAIImageAdapter`
  - `GeminiImageAdapter`
  - `GeminiOpenAIImageAdapter`
  - `ZImageAdapter`
  - `JimengImageAdapter`
  - `GrokImageAdapter`
  - `AgnesImageAdapter`
  - `create_adapter()`
- `generator.py`
  - `generate_image_with_fallback()`
  - 多目标重试、超时、错误脱敏、attempts 记录
- `models.py`
  - `ImageChannelConfig`
  - `ImageModelTarget`
  - `AICatConfig`
  - 模型优先级
- `provider_parser.py`
  - 从未知响应里提取图片 URL、base64、二进制图片
  - 处理 OpenAI-like、Gemini-like 和 provider 特殊响应

Android APP 不直接运行 Python，但要保留这些结构。

## Provider 类型

当前已接入生图类型和通用异步视频类型：

| 类型 | 能力 | 来源 |
| --- | --- | --- |
| openai_compatible_image | 文生图、图生图 | 已接入，含 `/v1/images/generations` 和 `/v1/images/edits` |
| gemini_image | 文生图、图生图 | 已接入，参考 GeminiImageAdapter |
| agnes_image | 文生图、参考图 | 已接入，参考 AgnesImageAdapter |
| grok_image | 文生图 | 已接入，参考 GrokImageAdapter |
| openai_image | 文生图、图生图 | 可作为 `openai_compatible_image` 别名/模板处理 |
| gemini_openai | OpenAI chat completions 兼容图像响应 | 未接入，后续按真实接口需求补 |
| openai_compatible_video | 同步或异步生视频 | 已作为 `generic_async_video` 别名接入 |
| grok_video | 异步生视频 | 已作为 `generic_async_video` 别名接入，具体路径可用扩展 JSON 覆盖 |
| seedance_video | 异步生视频 | 已作为 `generic_async_video` 别名接入，具体路径可用扩展 JSON 覆盖 |
| generic_async_video | 提交 job、轮询、下载结果 | 已接入 |

## 标准请求模型

```kotlin
data class GenerationRequest(
    val mode: GenerationMode,
    val prompt: String,
    val negativePrompt: String = "",
    val aspectRatio: String = "auto",
    val resolution: String = "1K",
    val count: Int = 1,
    val durationSeconds: Int? = null,
    val seed: Long? = null,
    val references: List<MediaReference> = emptyList(),
    val extra: JSONObject = JSONObject()
)

enum class GenerationMode {
    TEXT_TO_IMAGE,
    IMAGE_TO_IMAGE,
    TEXT_TO_VIDEO,
    IMAGE_TO_VIDEO
}
```

首版 UI 可以只显示常用参数，其余写入 `extra`。

## 标准结果模型

当前代码的 `GenerationResult` 已覆盖图片、视频、job、attempts、HTTP 状态、错误和响应预览：

```kotlin
data class GenerationResult(
    val status: GenerationStatus,
    val images: List<GeneratedAsset> = emptyList(),
    val videos: List<GeneratedAsset> = emptyList(),
    val job: ProviderJob? = null,
    val usedModel: String = "",
    val requestId: String = "",
    val error: String = "",
    val attempts: List<AttemptRecord> = emptyList(),
    val rawPreview: String = ""
)
```

约束：

- 所有远程 URL 结果都下载到本地缓存后再进入历史。
- 原始响应只保存预览和脱敏 JSON，完整响应可选保存到 `response_bodies/`。
- 错误信息要可读，避免只显示 HTTP code。

## 渠道配置

```json
{
  "name": "agnes",
  "provider_type": "agnes_image",
  "base_url": "https://apihub.agnes-ai.com",
  "api_key_ref": "keystore:channel/agnes/api_key",
  "default_model": "agnes-image-2.1-flash",
  "enabled_models": ["agnes-image-2.1-flash"],
  "timeout_seconds": 280,
  "enabled": true,
  "proxy": "",
  "extra": {}
}
```

`api_key_ref` 指向本机加密存储，导出配置时不导出明文。

## 生图适配规则

OpenAI-compatible：

- 文生图：`POST /v1/images/generations`
- 图生图：优先 `POST /v1/images/edits`
- 支持 `b64_json`、`url`、未知嵌套字段解析
- `gpt-image` 系列按参考插件的尺寸映射处理

Gemini：

- 请求使用 `contents.parts`
- 参考图作为 `inline_data`
- 响应从 `inlineData`、URL 或未知结构中解析图片

Agnes：

- 文生图走 OpenAI-like `images/generations`
- 参考图可传 data URL 或远程 URL
- 根据比例映射 size

Grok：

- payload 包含 `aspect_ratio`、`resolution`、`response_format`

## 生视频适配规则

状态：已实现最小闭环，并具备本地 MockWebServer submit/poll/download/失败脱敏回归。`GenericAsyncVideoAdapter` 支持提交 JSON 请求、从提交响应直接提取视频 URL 或解析 job id、按 poll URL/模板轮询、下载视频到本地缓存，并将 job id、poll URL、HTTP 状态、响应预览和 attempts 写入详情。应用级队列、前台服务通知、远端 job 恢复和视频缩略图均已完成。

生视频接口差异大，首版用通用异步 job 适配。

标准流程：

```text
submit(request) -> ProviderJob(jobId, pollUrl, resultUrl?)
poll(job) -> Running | Succeeded(remote assets) | Failed
download(remote assets) -> local files
```

`VideoProviderAdapter` 需要声明：

```kotlin
data class ProviderCapabilities(
    val textToImage: Boolean,
    val imageToImage: Boolean,
    val textToVideo: Boolean,
    val imageToVideo: Boolean,
    val synchronous: Boolean,
    val asyncJob: Boolean,
    val supportsModelList: Boolean,
    val supportsCancel: Boolean
)
```

内置视频模板：

- Grok 视频：`https://api.x.ai`，默认提交 `POST /v1/videos/generations`，轮询 `/v1/videos/generations/{id}`，使用通用视频 JSON 字段。
- Seedance：`https://ark.cn-beijing.volces.com`，提交 `POST /api/v3/contents/generations/tasks`，轮询 `/api/v3/contents/generations/tasks/{id}`，提示词与参数写入 `content` 数组。
- 渠道 `extra.video_provider` 选择请求映射；请求级 extra 可覆盖非内部字段。

通用字段映射：

| 标准字段 | 常见 provider 字段 |
| --- | --- |
| prompt | prompt, input, text |
| references | image, image_url, init_image, first_frame |
| durationSeconds | duration, seconds |
| aspectRatio | aspect_ratio, ratio |
| resolution | size, resolution, quality |
| seed | seed |

如果 provider 字段无法标准化，写入渠道 `extra.request_template`。

当前通用视频扩展字段：

| 字段 | 说明 |
| --- | --- |
| `video_submit_path` | 覆盖提交路径，默认 `/v1/videos/generations` |
| `video_poll_path_template` | 覆盖轮询路径模板，支持 `{job_id}` 或 `{id}` |
| `video_max_polls` | 最大轮询次数，默认 60 |
| `video_poll_interval_ms` | 轮询间隔，默认 5000ms |

## 响应解析策略

从插件迁移“宽松解析”：

1. 优先解析标准字段：`data[].b64_json`、`data[].url`、`images[]`、`videos[]`、`output[]`。
2. 扫描任意 JSON 字符串字段里的 URL。
3. 识别 data URL 和 base64 图片。
4. 对相对 URL 用响应 base URL 补全。
5. 下载前检查 MIME、Content-Length 和最大文件限制。
6. 失败时保存响应预览，提示“返回了链接但下载失败”或“未识别结果字段”。

## 模型优先级和回退

模型目标展开顺序：

1. 用户指定模型。
2. 渠道中启用模型优先级。
3. 所有启用渠道的所有启用模型。

`default_model` 仅保留为旧数据兼容字段，当前配置和创作流程以启用模型列表为准。

回退规则：

- 每次尝试记录 provider、model、耗时、HTTP 状态、错误。
- 成功后停止。
- 可配置最大尝试次数；默认至少覆盖所有启用目标一次。
- 全局超时优先于单次超时。
- 错误和记录必须脱敏。

## 子代理接口

```kotlin
interface AgentStep {
    val id: String
    val displayName: String
    suspend fun run(context: GenerationContext): AgentResult
}
```

首批子代理：

| 子代理 | 作用 |
| --- | --- |
| prompt_enhancer | 提示词增强 |
| reference_analyzer | 识别参考图，提取主体、风格、构图 |
| provider_router | 根据模式、比例、参考图和健康状态选择目标 |
| prompt_audit | 可选提示词审核 |
| output_audit | 可选结果审核 |
| metadata_writer | 写入 EXIF sidecar 或 JSON 元数据 |
| result_indexer | 写入历史和素材索引 |

子代理数量不写死在代码里。`AgentRegistry` 从内置步骤和配置文件加载。

## 安全与脱敏

必须脱敏：

- API Key
- Authorization
- Cookie
- Set-Cookie
- Proxy credential
- 完整请求头

诊断里允许保存：

- provider 类型
- base URL host
- model
- HTTP 状态
- 响应预览前若干字符
- request id
- 耗时

默认不把请求图和生成图上传到任何非当前 provider 的服务；提示词增强或审核子代理如果需要另一个 provider，UI 必须显示该子代理启用状态。

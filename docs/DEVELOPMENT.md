# AI Image Box 开发方案

## 当前仓库状态

`https://github.com/liyw0205/ai_image_box` 已进入 Android 原生工程开发。0.3.2 文生图初版增强已经完成，包含四 Tab 主界面、创作表单、任务队列取消/重试/清理、历史缩略图、参数复用、多图结果落盘、基础渠道管理、模型列表测试、API Key 加密保存、Termux 打包脚本和 logcat 脚本。

```text
/data/data/com.termux/files/home/devwork/ai_image_box
```

构建产物：

```text
/data/data/com.termux/files/home/devwork/AIImageBox_0.3.2_debug.apk
```

## 技术基线

参考 MediaVault 当前工程：

| 项目 | 决策 |
| --- | --- |
| 平台 | Android 原生 APP |
| 语言 | Kotlin |
| UI | XML layout + ViewBinding + Material Components |
| 最低系统 | Android 8.0, API 26 |
| 编译目标 | Android SDK 34 |
| JDK | 17 |
| 并发 | Kotlin Coroutines |
| 网络 | OkHttp |
| 本地文件 | 应用私有目录 + SAF 导出 |
| 任务后台 | ForegroundService, 后续可补 WorkManager |

建议包名：

```text
com.aiimagebox
```

建议首版依赖：

```kotlin
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
implementation("androidx.recyclerview:recyclerview:1.3.2")
implementation("androidx.activity:activity-ktx:1.9.3")
implementation("androidx.fragment:fragment-ktx:1.8.5")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

视频缩略图或预览播放可以后置引入 Media3。首版不急着加播放器内核。

## 工程目录建议

```text
app/src/main/java/com/aiimagebox/
  AIImageBoxApp.kt
  data/
    ChannelModels.kt
    TaskModels.kt
    AppSettings.kt
    LocalStores.kt
    SecureKeyStore.kt
  provider/
    ProviderAdapter.kt
    ProviderRegistry.kt
    ImageAdapters.kt
    VideoAdapters.kt
    ResponseParser.kt
  generation/
    GenerationManager.kt
    GenerationQueue.kt
    GenerationForegroundService.kt
    AgentPipeline.kt
  ui/
    MainActivity.kt
    StudioFragment.kt
    TasksFragment.kt
    HistoryFragment.kt
    ChannelsFragment.kt
    SettingsDrawerBinder.kt
  util/
    JsonExt.kt
    FileNames.kt
    Redaction.kt
    MimeDetect.kt
```

## 模块职责

| 模块 | 职责 |
| --- | --- |
| data | 配置、任务、记录、素材索引、加密密钥存取 |
| provider | 不同生图/生视频接口的请求构造、响应解析和下载 |
| generation | 队列、并发、重试回退、子代理流水线、前台服务 |
| ui | 创作、任务、历史、渠道管理、设置和数据页 |
| util | 脱敏、MIME、文件名、JSON、错误展示 |

## 数据存储

首版沿用 MediaVault 的轻量本地文件思路，不先引入数据库：

```text
files/ai_image_box/
  config/
    channels.json
    settings.json
    agent_pipeline.json
  records/
    generation_records.jsonl
    task_state.json
  cache/
    request_images/
    generated_images/
    generated_videos/
    response_bodies/
  diagnostics/
    latest_diagnostics.json
```

API Key 不明文写进可导出的 JSON：

- 本机保存：Android Keystore 加密。
- 导出配置：默认脱敏，仅保留渠道名、provider、base URL、模型和非敏感参数。
- 诊断包：必须走脱敏工具，不能包含 API Key、Authorization、Cookie。

## Provider 抽象

从 `astrbot_plugin_selfie_image` 迁移的核心思想：

- `ImageModelTarget` -> Android `ModelTarget`
- `ImageGenerateRequest` -> Android `GenerationRequest`
- `ImageGenerateResult` -> Android `GenerationResult`
- `BaseImageAdapter` -> Android `ProviderAdapter`
- `create_adapter()` -> Android `ProviderRegistry`
- `generate_image_with_fallback()` -> Android `GenerationManager.generateWithFallback()`

首版接口：

```kotlin
interface ProviderAdapter {
    val type: String
    suspend fun listModels(channel: ProviderChannel): ModelListResult
    suspend fun generate(request: GenerationRequest, target: ModelTarget): GenerationResult
    suspend fun poll(job: ProviderJob, target: ModelTarget): GenerationResult
    fun capabilities(channel: ProviderChannel): ProviderCapabilities
}
```

`poll()` 用于异步视频接口。同步生图接口可以直接返回 `UnsupportedOperation` 或空实现。

## 子代理流水线

子代理不做固定数量限制，采用 registry 加配置：

```text
PromptInput
  -> PromptEnhancerAgent
  -> ReferenceAnalyzerAgent
  -> ProviderRouterAgent
  -> PromptAuditAgent, optional
  -> ProviderAdapter
  -> OutputAuditAgent, optional
  -> MetadataWriterAgent
  -> ResultIndexAgent
```

工程规则：

- 子代理可以禁用、排序、复制、扩展。
- APP 不限制子代理总数。
- 执行时只受并发设置、任务取消、超时和设备资源限制。
- 每个子代理写入 `attempts` 和耗时，方便复现问题。

## 生成队列

`GenerationManager` 维护全局队列：

- 默认并发：3，可配置。
- 单任务全局超时：默认 280 秒，可配置。
- 单文件下载上限：默认 100 MB，可配置。
- 自动回退：按模型优先级轮询 provider。
- 失败策略：保留最后错误和每次尝试摘要。
- 视频异步任务：提交成功后进入 `Polling`，轮询间隔指数退避，用户可取消。

注意：这些是稳定性保护，不是 token 或额度限制。

## UI 方案

参考 MediaVault：

- 一个 APK，一套业务。
- 竖屏：底部导航。
- 横屏或大屏：左侧 NavigationRail，内容区两栏或三栏。
- 设置、数据、子代理管理从右侧抽屉进入，不新增过多底栏 Tab。
- 深色工作台风格，重点是密集但清晰的任务和结果管理。

首屏直接是“创作”工作台：

- 顶部：模式切换、provider/model 选择、参数摘要。
- 中部：提示词、参考图、比例、分辨率、数量、时长。
- 底部：提交按钮、最近结果、当前任务进度。

## 构建脚本建议

参考 MediaVault 的 Termux 约束，新增脚本：

```text
pack_ai_image_box.sh
log_ai_image_box.sh
```

Termux 下统一用打包脚本，不裸跑 `./gradlew`。脚本负责：

- 设置 Android SDK/JDK 环境。
- 临时处理 aapt2。
- 执行 `assembleDebug --no-daemon`。
- 复制 APK 到 `../AIImageBox_<version>_debug.apk`。

## 开发顺序

1. 创建 Android 工程骨架和基础壳。
2. 实现本地配置和记录存储。
3. 实现 provider registry 和 OpenAI-compatible 生图。
4. 实现创作页、渠道页和任务页。
5. 接入任务队列、重试回退和结果缓存。
6. 增加图生图、Gemini、Agnes、Grok 等 adapter。
7. 增加异步生视频 adapter 抽象和至少一个 provider。
8. 增加历史、诊断包、配置导出导入。
9. 横屏融合态、D-pad 焦点和真机回归。

## 验收清单

每个版本至少验证：

- 冷启动和空配置状态。
- 添加、编辑、禁用、删除渠道。
- 拉取模型列表和测试连接。
- 文生图成功、失败、超时、取消。
- 图生图参考图上传和请求构造。
- 生视频提交、轮询、下载、失败重试。
- 历史记录可筛选、可复用参数。
- 缓存清理不误删配置。
- 诊断包脱敏。
- 竖屏触控和横屏 D-pad 基础可用。

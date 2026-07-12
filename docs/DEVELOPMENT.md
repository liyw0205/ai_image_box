# AI Image Box 开发方案

## 当前仓库状态

`https://github.com/liyw0205/ai_image_box` 已进入 Android 原生工程开发。0.8.0 诊断与配置迁移已经完成，包含脱敏诊断包、配置导入导出、缓存统计、代理配置 Tab、AgentRegistry、AgentPipeline、四类内置代理和执行记录、历史时间范围、素材详情、历史模式/状态筛选、provider/模型/提示词搜索、批量缓存清理、缺失文件提示、结果作为参考图、系统级 ForegroundService 通知与队列保活、Activity 销毁后继续执行的应用级队列、远端视频 job 持久化与重启续轮询、视频首帧缩略图、时长和尺寸落库、创作及历史视频预览、通用异步视频 adapter、模型类型自动切换文生视频/图生视频、submit/poll/download、视频结果落盘、公共 Movies 导出、视频 job/轮询详情记录、接口模板、API Key/Base URL 快速校验、模型类型批量修改、创作页渠道/模型选择记忆、GitHub Actions 打包 APK 并发布到 Releases、参考 Neribox UI 的浅灰蓝配色、参考图参数落库和详情复用、卡片式提示词预设、预设使用/编辑、OpenAI-compatible、Gemini、Agnes、Grok 图像适配器、跨渠道/同渠道失败自动回退、attempts 记录、自定义预设、创作表单模型选择弹窗、可选参考图、多图预览、公共目录导出、任务队列取消/重试/清理、重启恢复、请求/响应详情、常见 provider 错误中文化、历史缩略图、参数复用、多图结果落盘、基础渠道管理、扩展 JSON 可视化/JSON 切换、已配置模型类型可视化编辑、上游模型拉取/筛选/保存、模型类型推断与手动修改、API Key 加密保存、Termux 打包脚本和 logcat 脚本。

```text
/data/data/com.termux/files/home/devwork/ai_image_box
```

构建产物：

```text
/data/data/com.termux/files/home/devwork/AIImageBox_0.8.0_debug.apk
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
| 本地文件 | 应用私有目录 + MediaStore 结果导出 + SAF 配置/诊断导入导出 |
| 任务后台 | 当前生图和生视频队列由 Application 生命周期持有；已接入 ForegroundService 通知和系统级任务保活，可再补 WorkManager |

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

视频首帧已使用系统 MediaMetadataRetriever 提取；完整播放仍可后置引入 Media3。

## 当前工程目录

```text
app/src/main/java/com/aiimagebox/
  AIImageBoxApp.kt
  MainActivity.kt
  data/
    AppDirectories.kt
    ChannelModels.kt
    GenerationModels.kt
    ChannelStore.kt
    GenerationStore.kt
    SecureKeyStore.kt
  provider/
    ProviderAdapter.kt
    ProviderRegistry.kt
    OpenAICompatibleImageAdapter.kt
    JsonImageAdapters.kt
    GenericAsyncVideoAdapter.kt
    ResponseParser.kt
    ProviderErrorLocalizer.kt
  generation/
    GenerationManager.kt
    GenerationQueue.kt
  ui/
    StudioForm.kt
  util/
    JsonFiles.kt
    PublicMediaExporter.kt
    Redaction.kt
```

## 模块职责

| 模块 | 职责 |
| --- | --- |
| data | 配置、任务、记录、素材索引、加密密钥存取 |
| provider | OpenAI-compatible、Gemini、Agnes、Grok 生图请求构造、响应解析和下载；通用异步视频 submit/poll/download |
| generation | 队列、取消、重试、恢复、同/跨渠道回退、视频模式路由和 attempts 汇总；前台轮询服务未接入 |
| ui | `MainActivity` 承载创作、任务、历史、渠道管理；`StudioForm` 承载创作表单 |
| util | 脱敏、JSON 文件、公共图片目录导出 |

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
- 导出配置：默认脱敏，仅保留渠道名、provider、base URL、模型和非敏感参数，通过系统文件创建器保存到用户选择的位置。
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
    suspend fun generate(channel: ProviderChannel, request: GenerationRequest, target: ModelTarget): GenerationResult
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
- 视频异步任务：已接入应用级 submit/poll/download 闭环、远端 job 恢复和前台服务通知；内置 Grok 通用 JSON 与 Seedance `content` 请求映射，接口路径可由渠道扩展 JSON 覆盖。

注意：这些是稳定性保护，不是 token 或额度限制。

## UI 方案

参考 MediaVault：

- 一个 APK，一套业务。
- 竖屏：底部导航。
- 横屏或大屏：左侧 NavigationRail；非触控模式默认聚焦导航，方向键可进入内容操作区，滚动容器跟随焦点。
- 设置、数据、子代理管理后续从右侧抽屉进入，不新增过多底栏 Tab。
- 当前采用 Neribox 浅灰蓝风格：浅灰页面底、白色面板、灰色分隔线、蓝色主操作，重点是密集但清晰的任务和结果管理。

首屏直接是“创作”工作台：

- 顶部：provider/model 选择和参数摘要；当前模式由是否选择参考图自动映射为文生图/图生图。
- 中部：提示词、预设、参考图、比例、分辨率、数量；视频时长等细分参数后续补独立控件或扩展参数入口。
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

## GitHub Actions 发布

仓库包含 `.github/workflows/android-release.yml`：

- 推送 `v*` 标签时自动构建 APK 并发布到 GitHub Releases。
- 也可以在 Actions 页面手动运行，留空 tag 时默认使用 `v<versionName>`。
- 发布产物命名为 `AIImageBox_<versionName>_debug.apk`。

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

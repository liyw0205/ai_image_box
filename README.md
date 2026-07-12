# AI Image Box

AI Image Box 是一个 Android 原生 APP，用于统一管理多接口生图和生视频任务。

当前仓库已进入功能迭代阶段，开发文档按当前代码状态维护，作为后续开发和验收基线。

当前开发基线：`0.6.0` Android 原生多图像 provider 和通用异步视频 provider，包含生视频 submit/poll/download 最小闭环和远端 job 重启续轮询和应用级后台队列、ForegroundService 通知、历史筛选和结果参考图复用、视频结果落盘和公共 Movies 导出、视频 job/轮询详情记录、渠道模板、API Key 快速校验、模型类型批量修改、跨渠道自动回退、创作页渠道/模型选择记忆、GitHub Actions 打包 APK 并发布到 Releases、参考 Neribox UI 的浅灰蓝配色、参考图参数落库和详情复用、卡片式提示词预设、预设使用/编辑、OpenAI-compatible、Gemini、Agnes、Grok 图像适配器、attempts 记录、自定义预设、创作表单模型选择弹窗、可选参考图、多图预览、公共目录导出、任务队列取消/重试/清理、重启恢复、请求/响应详情、常见 provider 错误中文化、历史缩略图、参数复用、多图结果落盘、基础渠道管理、扩展 JSON 可视化/JSON 切换、已配置模型类型可视化编辑、上游模型拉取/筛选/保存、模型类型推断与手动修改、API Key 加密保存、Termux 打包脚本和 logcat 脚本。

## 文档入口

- `docs/APP_SPEC.md`：产品目标、信息架构、核心流程和范围边界。
- `docs/DEVELOPMENT.md`：Android 工程方案、模块划分、数据模型、构建方式和验收清单。
- `docs/PROVIDER_ADAPTERS.md`：多接口生图/生视频 provider 适配层设计。
- `docs/MEDIAVAULT_REFERENCE.md`：从本地 MediaVault 复用的工程和 UI 经验。
- `docs/ROADMAP.md`：版本阶段和下一步执行顺序。

## 参考来源

- Android APP 形态参考：`/data/data/com.termux/files/home/devwork/MediaVault_git`
- 生图接口参考：`/data/data/com.termux/files/home/astrbot_plugin_selfie_image`

## 基线决策

- 使用 Android 原生 Kotlin + XML/ViewBinding，不做 WebView 壳。
- 参考 MediaVault 的一个 APK、竖屏触控态、横屏融合态和本地数据管理方式；当前 UI 配色以 Neribox 浅灰蓝风格为准。
- 参考 `astrbot_plugin_selfie_image` 的 provider 抽象、模型优先级、重试回退、请求/响应记录和 Web 管理能力，迁移为 Android 本地实现。
- 产品模式不设置客户端 token 额度、日限额或子代理数量上限；只保留超时、并发、缓存和系统资源类保护。

## 构建

Termux / 本地工作区：

```bash
bash pack_ai_image_box.sh
```

产物：

```text
../AIImageBox_0.6.0_debug.apk
```

## GitHub Release 打包

`.github/workflows/android-release.yml` 会在推送 `v*` 标签或手动运行时构建 debug APK，并把 `AIImageBox_<version>_debug.apk` 发布到 GitHub Releases。

常规 Android 环境：

```bash
./gradlew assembleDebug --no-daemon
```

## 调试日志

```bash
./scripts/log_ai_image_box.sh
./scripts/log_ai_image_box.sh 120
```

# AI Image Box

AI Image Box 是一个计划中的 Android 原生 APP，用于统一管理多接口生图和生视频工作流。

当前仓库远端只有最小 README。本地已补齐第一版开发文档，作为后续开工程骨架和实现功能的基线。

当前开发基线：`0.3.2` Android 原生 OpenAI-compatible 文生图初版增强，包含四 Tab 主界面、创作表单、任务队列取消/重试/清理、历史缩略图、参数复用、多图结果落盘、基础渠道管理、模型列表测试、API Key 加密保存、Termux 打包脚本和 logcat 脚本。

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
- 参考 MediaVault 的一个 APK、竖屏触控态、横屏融合态、深色工作台 UI 和本地数据管理方式。
- 参考 `astrbot_plugin_selfie_image` 的 provider 抽象、模型优先级、重试回退、请求/响应记录和 Web 管理能力，迁移为 Android 本地实现。
- 产品模式不设置客户端 token 额度、日限额或子代理数量上限；只保留超时、并发、缓存和系统资源类保护。

## 构建

Termux / 本地工作区：

```bash
bash pack_ai_image_box.sh
```

产物：

```text
../AIImageBox_0.3.2_debug.apk
```

常规 Android 环境：

```bash
./gradlew assembleDebug --no-daemon
```

## 调试日志

```bash
./scripts/log_ai_image_box.sh
./scripts/log_ai_image_box.sh 120
```

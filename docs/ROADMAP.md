# AI Image Box 路线图

## 0.1.0 工程骨架

状态：已完成。`bash pack_ai_image_box.sh` 已成功生成 `../AIImageBox_0.1.0_debug.apk`。

目标：先跑起来一个可安装 APP。

- Android Gradle 工程。
- `com.aiimagebox` 包名。
- MainActivity + 四 Tab。
- 深色主题和基础布局。
- 应用私有目录初始化。
- Termux 打包脚本。

验收：

- `bash pack_ai_image_box.sh` 生成 debug APK。
- 冷启动显示“创作”工作台。
- 竖屏底栏、横屏导航轨可切换。

## 0.2.0 渠道管理

状态：基础渠道管理已完成。已实现 `ProviderChannel`、`ModelTarget`、`channels.json`、Android Keystore API Key 加密保存、渠道新增、编辑、复制、启用、禁用、删除和渠道页列表。测试连接和模型列表下沉到 0.2.1。

目标：本地可配置 provider。

- ProviderChannel 数据模型。
- 本地 `channels.json`。
- API Key 加密保存。
- 渠道列表、新增、编辑、复制、禁用、删除。
- 测试连接和模型列表。
- 配置导出时脱敏。

验收：

- 添加 OpenAI-compatible 渠道。
- 保存后重启仍存在。
- 删除渠道不影响历史文件。

## 0.3.0 文生图

目标：完成第一条端到端生成链路。

- OpenAI-compatible image adapter。
- 标准 `GenerationRequest` / `GenerationResult`。
- `GenerationManager` 队列。
- 创作页提交文生图。
- 结果下载到本地缓存。
- 失败记录和错误展示。

验收：

- 文生图成功显示图片。
- HTTP 失败显示响应预览。
- 超时可恢复，任务可重试。

## 0.4.0 图生图和多 provider 回退

目标：迁移插件的主要生图能力。

- 图生图参考图。
- Gemini image adapter。
- Agnes image adapter。
- Grok image adapter。
- 模型优先级。
- 多 provider 尝试记录。

验收：

- 参考图参与请求。
- 第一个 provider 失败后自动尝试下一个。
- 历史记录里能看到 attempts。

## 0.5.0 生视频抽象

目标：支持异步生视频。

- `VideoProviderAdapter`。
- submit/poll/download 通用流程。
- `ProviderJob` 持久化。
- 轮询前台服务。
- 取消本地轮询。
- 视频结果保存和缩略图。

验收：

- 文生视频任务可从 Running 进入 Polling。
- APP 重启后可恢复轮询。
- 成功后下载视频，失败后保留 job id 和错误。

## 0.6.0 历史与素材库

目标：让结果可管理、可复用。

- 历史筛选：时间、模式、provider、模型、成功/失败。
- 复用参数再次生成。
- 以结果作为参考图/视频。
- 批量清理缓存。
- 素材详情。

验收：

- 1000 条记录列表不卡顿。
- 清理缓存不删除配置。
- 记录里的本地文件缺失时有明确状态。

## 0.7.0 子代理流水线

目标：支持无限扩展的生成前后处理。

- `AgentRegistry`。
- `AgentPipeline` 配置。
- 提示词增强子代理。
- 参考图分析子代理。
- 可选审核子代理。
- 元数据写入子代理。

验收：

- 可启用/禁用/排序子代理。
- 子代理不写死数量上限。
- 每个子代理耗时和输出进入任务记录。

## 0.8.0 诊断、导入导出与稳定化

目标：可维护、可迁移、可定位问题。

- 诊断包。
- 配置导出导入。
- 请求/响应脱敏。
- 缓存占用统计。
- 横屏 D-pad 回归。
- 常见 provider 错误中文化。

验收：

- 诊断包不含 API Key。
- 导入配置后渠道可用，缺 key 有明确提示。
- 横屏键盘和遥控可完成基础流程。

## 当前下一步

1. 开始 0.2.1 渠道测试和模型列表。
2. 实现 OpenAI-compatible `/v1/models` 拉取。
3. 增加渠道测试结果、HTTP 错误预览和脱敏显示。
4. 为 0.3.0 文生图 adapter 准备 `ProviderAdapter` 和 `ProviderRegistry`。

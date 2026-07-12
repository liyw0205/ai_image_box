# 当前目标窗口

本目录采用固定五项滚动窗口管理开发。每个目标独立完成实现、自动测试、APK 构建、文档状态更新、中文提交和推送；完成一项后补充新的第五项，保证始终保留五个已排序目标。

| 顺序 | 文件 | 状态 | 目标 |
| --- | --- | --- | --- |
| 01 | `01_reference_media_capability.md` | 已完成（0.8.7） | 参考素材能力校验 |
| 02 | `02_history_batch_reuse.md` | 已完成（0.8.8） | 历史筛选批量复用 |
| 03 | `03_video_contract_mock_regression.md` | 已完成（0.8.9） | 视频接口 Mock 回归 |
| 04 | `04_channel_template_validation.md` | 已完成（0.8.10） | 渠道模板本地校验 |
| 05 | `05_release_regression_evidence.md` | 已完成（0.8.11） | 发布与设备回归证据 |
| 06 | `06_generated_asset_integrity.md` | 待开始 | 生成结果文件完整性校验 |
| 07 | `07_agent_execution_readability.md` | 待开始 | 子代理执行记录可读性 |
| 08 | `08_storage_schema_migration.md` | 待开始 | 本地存储架构迁移校验 |
| 09 | `09_public_export_naming.md` | 待开始 | 公共导出文件名与冲突策略 |
| 10 | `10_history_file_repair.md` | 待开始 | 历史文件修复与清理 |

外部账户和真机验证不会阻塞本地可实现功能；相关目标会先建立可复现的自动化契约和证据格式，再在可用环境中执行。

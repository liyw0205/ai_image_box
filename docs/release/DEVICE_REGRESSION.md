# 设备与发布回归记录

每个 Release 或候选 APK 使用一份本地副本记录以下项目。该文件是模板，不填写 API Key、Cookie、个人路径或账号信息。

## APK 证据

- APK 文件名：
- `scripts/verify_apk_metadata.sh` 输出：
- 安装设备 / Android 版本：
- 安装时间：

## 横屏 D-pad

- [ ] 冷启动焦点位于导航组件。
- [ ] 左右方向可在 NavigationRail 与页面操作区间移动。
- [ ] 焦点控件会自动滚入可视区域。
- [ ] 创作、任务、历史、渠道、代理五个页面均可访问。

## 文件导出

- [ ] 配置 JSON 通过系统创建器保存。
- [ ] 诊断 ZIP 通过系统创建器保存，且不含 API Key。
- [ ] 图片和视频结果可导出到公共媒体目录。

## 视频与渠道

- [ ] Grok 或 Seedance 模板能完成真实 submit、poll、download。
- [ ] 失败时历史详情显示脱敏响应和 attempt。
- [ ] 未验证时记录阻塞原因，不把凭据写入仓库。

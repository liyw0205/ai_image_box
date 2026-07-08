# MediaVault 参考映射

## 已检查的本地参考

```text
/data/data/com.termux/files/home/devwork/MediaVault_git
/data/data/com.termux/files/home/devwork/MediaVault
/data/data/com.termux/files/home/devwork/MediaVault开发.md
```

MediaVault 当前是 Android 原生 Kotlin 项目：

- `app/build.gradle.kts`：Android application、Kotlin、SDK 34、minSdk 26、ViewBinding、Material、OkHttp、Coroutines。
- `app/src/main/AndroidManifest.xml`：一个主 Activity，附加播放 Activity 和前台服务。
- `app/src/main/java/com/mediavault/ui/MainActivity.kt`：底部导航、横屏导航轨、抽屉、Fragment 切换、旋转恢复。
- `app/src/main/java/com/mediavault/scrape/ScrapeForegroundService.kt`：长任务前台服务思路。
- `app/src/main/java/com/mediavault/data/*`：本地 JSON、诊断、导出导入和缓存管理思路。
- `app/src/main/res/values/colors.xml`：原深色工作台配色参考，AI Image Box 0.4.3 改为 Neribox 浅色系。

## 应复用的做法

| MediaVault 做法 | AI Image Box 用法 |
| --- | --- |
| 一个 APK、一套业务 | 不拆手机/平板/TV 多包 |
| 竖屏底栏 + 横屏 NavigationRail | 创作、任务、历史、渠道四 Tab |
| 右侧抽屉承载高级设置 | 设置、数据、子代理、诊断从抽屉进入 |
| ViewBinding + XML | 首版保持稳定和可控，不引入 Compose |
| ForegroundService 管长任务 | 生视频轮询、批量下载、长生图任务 |
| 本地 JSON 和应用私有目录 | 渠道、任务、历史、缓存和诊断 |
| 诊断包脱敏 | provider 请求、响应、错误和缓存状态 |
| 横屏 D-pad 焦点回归 | 平板、电视盒子和键盘操作兼容 |
| Termux 打包脚本 | `pack_ai_image_box.sh` 统一构建 |
| 首页工作流区 | 创作页增加工作流面板，把入口、状态、步骤和结果归档放在同一条用户路径里 |

## 不应复用的做法

| MediaVault 内容 | 原因 |
| --- | --- |
| 视频媒体库刮削 | AI Image Box 不管理影视库 |
| TMDB/NFO/合集逻辑 | 领域不相关 |
| SMB/FTP/WebDAV 远程播放 | 首版不做远程媒体库 |
| Media3 播放器内核 | 首版只需要视频预览和系统分享，可后置 |
| 字幕/音轨/连播 | 领域不相关 |

## UI 映射

MediaVault 的四 Tab：

```text
主页 / 搜索 / 合集 / 刮削
```

AI Image Box 的四 Tab：

```text
创作 / 任务 / 历史 / 渠道
```

MediaVault 的刮削侧栏功能密度较高，AI Image Box 可以参考为：

```text
渠道 Tab 顶栏设置 -> 右侧抽屉
  - 全局设置
  - 子代理流水线
  - 数据占用
  - 诊断包
  - 导出/导入配置
```

## 配色约束

0.4.3 起主配色改为参考 Neribox UI 的浅灰蓝：浅灰页面底、白色面板、灰色边线、蓝色主操作。MediaVault 继续作为工作流组织方式参考，不再直接照搬深色工作台颜色。

推荐角色：

| 角色 | 用途 |
| --- | --- |
| bg | 页面底色 |
| surface | 面板、列表、抽屉 |
| surface2 | 次级面板、输入区 |
| text | 主文本 |
| textSecondary | 次文本 |
| primary | 主操作、选中、焦点 |
| danger | 删除、取消远端任务 |
| success | 成功状态 |
| warning | 可恢复错误、轮询中 |

新增 UI 要检查：

- 背景和文字保持足够对比，避免浅底浅字或深底深字。
- 按钮文字不溢出。
- 横屏抽屉不遮挡主任务状态。
- D-pad 焦点可见，焦点顺序可预测。

## 构建与发布借鉴

MediaVault 在 Termux 下要求用 `bash pack_mediavault.sh`，避免裸跑 Gradle 后 aapt2 配置残留。

AI Image Box 也应建立同类规则：

```bash
bash pack_ai_image_box.sh
```

脚本输出：

```text
../AIImageBox_<version>_debug.apk
```

每次功能完成后同步：

- `README.md`
- `docs/ROADMAP.md`
- 版本号和 APK 名
- 已知问题和下一步

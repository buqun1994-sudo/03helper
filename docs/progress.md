# 03helper 进度

## 2026-08-18 底座初始化

1. 已建立独立本地仓库目录 `03helper`，并记录从 `03lyrics` 复制上下文、约束、文档路由、规则和工作流的来源锚点。
2. 已排除 Android 产品源码、APK、构建产物、密钥、个人绝对路径、设备地址、日志和 03lyrics 商业 / 歌词历史。
3. 初始化时只确认安装助手的长期边界和文档底座；具体 APK 分发源、无线调试、OTG、授权动作和 UI 细节当时仍待产品与架构决策，已在本轮方案落档中更新其中的主链与安全边界。
4. 已按 `03lyrics` 的 GitHub owner 和公开可见性创建远端 `buqun1994-sudo/03helper`，本地 `origin` 已指向 `https://github.com/buqun1994-sudo/03helper.git`；本轮未提交、未推送、未创建 Release。

## 2026-08-18 首版安装助手方案落档

1. 已把“03应用安装助手”确定为独立手机 Android 应用的产品边界；不把 03 歌词、03桌面或文件管理器源码并入本仓库。
2. 已记录首次安装主链：局域网优先发现 / 连接设备，用户选择组件（03 歌词和 03桌面必装且不可取消，文件管理器可选），随后由助手自动完成下载页解析、ZIP 下载与校验、APK 解压与校验、ZIP 删除、非流式推送、安装、白名单授权、启动和可用性验证，成功后转入维护态。
3. 已记录分发初始方案：助手本体单独下载；组件安装包使用签名版本清单和外部对象流量，不使用自有服务器每月 600GB 流量作为安装包直链主分发。源顺序已在后续 ZIP 主链决策中定稿，尚未接入代码或生产服务。
4. 已记录当前样本车机实测事实：`S56_HQX`、Android 9、TCP `5555` 常驻开放、`ro.adb.secure=0`。首次连接前不承诺自动打开无线调试；OTG 尚无能力证据，暂不进入正式主链。
5. 已记录安全不变量：远端清单不得下发任意 shell；授权必须读取原值、幂等追加并回读确认；ZIP 与 APK 完整校验后才可非流式推送；失败与未知状态必须 fail closed。
6. 已记录发布阻断：03桌面尚无长期 Release 签名、03 歌词尚未建立 production 发布身份、文件管理器仍为 Debug 身份且正式分发需履行 GPL 义务。
7. 本轮只更新文档和索引，没有建立 Android 工程、下载服务、ADB 适配器、签名身份或发布产物；任何相关描述均为方案 / 待实施状态。
8. 本轮文档验证已执行并通过：`node scripts/check-project-docs.mjs`、`node scripts/check-skills.mjs`、`git diff --check`，另完成 34 个底座文件的 UTF-8 与尾空白检查；因没有 Android 代码或可安装产物，未执行设备安装和运行 smoke。

## 2026-08-18 蓝奏云 ZIP 主链与施工文案定稿

1. 用户在真实安卓手机上验证：免费蓝奏云手机端直接下载 APK 会被限制，但扫码进入无密码 ZIP 分享页后，下载与解压流程顺利。因此正式选择“每个组件一个无密码 ZIP”而非“每个组件一个 APK”作为蓝奏云自动主链。
2. 三个 ZIP 测试分享页已记录为后续 fixture，当前不猜测其组件映射：`https://wwatl.lanzouw.com/icExq435o4sj`、`https://wwatl.lanzouw.com/i9neI435o4zg`、`https://wwatl.lanzouw.com/iGrHV435o5ah`。
3. 用户确认的应用内体验是：不打开外部浏览器，安装助手在进度页背后用 Android System WebView 的默认手机端标识完成正常蓝奏云页面流程，取得 ZIP 下载回调后由应用自有下载器继续下载；用户只看到统一进度。
4. 已把发布与缓存规则定为：同一单组件 ZIP 镜像到蓝奏云、R2 与 GitHub Releases；先校验 ZIP，再解压唯一 APK 并校验 APK，校验通过后立即删除 ZIP；已校验 APK 仅保留给当前可恢复安装会话。
5. 已补齐 Cloud Android ZIP 发布契约、产品基线、架构 owner、安全边界、V1 浅蓝 UI 施工文案、验证矩阵和本地发布口径。Cloud 现有 release index / R2 / GitHub 能力作为控制面与镜像能力入口，但 Android ZIP profile 尚未施工。
6. 本轮仍仅更新文档；没有建立 Android 工程、隐藏 WebView、下载器、解压器、ADB 适配器、实际清单或发布产物。用户的人工手机 ZIP 验证已记录，安装助手内的自动回调与解压闭环仍待运行级验证。
7. 用户进一步冻结视觉与动效：首次安装是整屏浅蓝背景与白色内容；维护态延续蓝色背景并改用大号、极简、按用途分组的分类按钮；页面、按钮、加载、进度和结果的进入 / 退出 / 点击全部必须使用基础非线性动画，禁止静态突变。
8. 本轮文档护栏已通过：`node scripts/check-project-docs.mjs`、`node scripts/check-skills.mjs`、三个脚本 `node --check`、`git diff --check` 和全量文件 UTF-8 / 尾空白检查；未执行 Android 构建、隐藏 WebView smoke、ADB 安装或车机验证，因为当前仓库仍无 Android 工程与可安装产物。

## 2026-08-18 F0 Android 工程与实机 smoke

1. 已建立可运行的单模块 Kotlin / Jetpack Compose 手机工程：`app`、`applicationId/namespace=com.tcrrry.helper`、`minSdk=26`、`compileSdk/targetSdk=36`、JDK 17、Gradle 8.11.1、AGP 8.9.2、Kotlin / Compose plugin 2.1.0、版本 `0.1.0 (1)`。03 歌词和 03桌面的 Android 9 车机目标没有下沉为助手手机端的 targetSdk。
2. 已落地 F0 的领域快照契约与唯一 UI 投影：`InstallationSessionState` / `InstallationSessionSnapshot` -> `InstallUiStateMapper` -> `InstallApp`；页面只发送 `InstallUiIntent`，没有接入 ADB、WebView、下载协议或第二套生产状态机。
3. 已落地蓝底白字视觉基座、尺寸 / 颜色 / 字体 / 动效令牌、显式 Lucide 图标注册、首次安装五类页面和维护态三组大号动作。页面进入 `220ms`、退出 `180ms`、按下 `90ms`、释放 `160ms`、状态变化 `160ms`、进度追随 `180ms`，列表错峰 `24ms`；维护态在当前手机内容宽度约 `344dp` 下实机呈单列，`360dp` 阈值规则由 JVM 测试覆盖。
4. Debug 变体已提供 `searching`、`found`、`selection`、`progress`、`success`、`paused`、`failed`、`maintenance`、`maintenance-disconnected` 和 `flow` 场景；`flow` 已补齐“发现 -> 选择确认 -> 获取 -> 校验 -> 安装 -> 配置 -> 验证 -> 成功”的确定性推进，并仍复用领域快照与同一 UI 映射。Release 合并清单不包含 Debug 场景 Activity。
5. F0 主源码清单不声明网络、存储、ADB、WebView 或无障碍业务权限；AndroidX 合并清单自动生成的签名级动态接收器权限属于框架产物。已补充 Android 12+ 云备份 / 设备迁移排除规则，安装助手私有数据不进入备份。
6. 本机验证已通过：`node scripts/check-local-environment.mjs`、`node scripts/check-project-docs.mjs`、`node scripts/check-skills.mjs`、`git diff --check`、`./gradlew :app:lintDebug`、`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`。Lint 仅保留已锁定依赖版本的更新提示，没有错误。
7. 已在指定手机测试设备上使用显式 serial 保留数据覆盖安装 Debug APK 与测试 APK；设备为 `SM-F946B`、Android SDK 36、`904x2316`、`420dpi`。生产 `MainActivity` 冷启动返回 `Status: ok`，保持前台，日志未发现应用致命异常；首屏、选择、进度、成功、暂停、失败、维护和断线维护画面均完成真实截图检查，整屏背景与状态栏 / 导航栏保持 `#1976C5`，未见文本截断、重叠或问号回退图标。
8. 真实交互 smoke 已完成：选择页取消可选文件管理器后摘要从 3 个应用变为 2 个，03 歌词和 03桌面仍不可取消；点击“开始安装”进入安装阶段；维护态滚动后全部三组动作可见，断线态显示“重新连接”。
9. 指定手机上的 instrumentation 已通过 `2/2`：生产入口和 Debug 维护场景均到达 resumed 状态。测试 APK 显式加入 `androidx.test:runner:1.5.2`，不再出现运行器缺失导致的假失败。
10. F0 只证明手机工程、状态投影、视觉壳、动效和启动可运行；真实局域网发现、组件下载 / 解压 / 哈希、ADB 上传安装、授权回读、车机可用性验证、Cloud 发布链和正式签名仍未施工。
11. F0 基座已在提交 `948f51d` 固化；提交前完成暂存区差异检查，未包含本机上下文、设备地址、截图或构建产物。

## 未完成事项

1. 完成真实 `InstallationSession` 状态机、首次安装应用流程，以及局域网发现 / ADB 端口适配。
2. 建立隐藏 Android System WebView 蓝奏云 ZIP 适配器、应用内下载器、`ArtifactArchiveExtractor`、签名版本清单、R2 / GitHub Releases ZIP 自动备用和回滚链；确认客户端不会打开外部浏览器或把夸克页面当自动协议。
3. 为 03 歌词、03桌面和文件管理器取得可验证的包身份、兼容范围、发布签名和合规材料。
4. 用真实 `S56_HQX` 设备完成定向安装、授权回读、启动可用性和断线恢复 smoke；用独立设备验证异常和破坏性动作门禁。
5. 完成维护态的检查更新、保留数据重装、修复授权、重启服务、缓存清理、受控安装 / 卸载和脱敏诊断。

## F1 交接入口

下一轮从 `docs/plans/V1应用功能与UI施工文案.md` 的 F1 小节开始：先实现唯一 `InstallationSession` 与结构化 command / fake port，接通生产 `MainActivity` 和 Debug `flow`，再以状态转移单测和手机运行 smoke 验证；真实下载、WebView、LAN、ADB、授权和车机安装保持在 F2 / F3，不得提前把外部协议写进 UI 或领域状态机。

# 03helper 进度

## 2026-08-18 底座初始化

1. 已建立独立本地仓库目录 `03helper`，并记录从 `03lyrics` 复制上下文、约束、文档路由、规则和工作流的来源锚点。
2. 已排除 Android 产品源码、APK、构建产物、密钥、个人绝对路径、设备地址、日志和 03lyrics 商业 / 歌词历史。
3. 初始化时只确认安装助手的长期边界和文档底座；具体 APK 分发源、无线调试、OTG、授权动作和 UI 细节当时仍待产品与架构决策，已在本轮方案落档中更新其中的主链与安全边界。
4. 已按 `03lyrics` 的 GitHub owner 和公开可见性创建远端 `buqun1994-sudo/03helper`，本地 `origin` 已指向 `https://github.com/buqun1994-sudo/03helper.git`；本轮未提交、未推送、未创建 Release。

## 2026-08-18 首版安装助手方案落档

1. 已把“03应用安装助手”确定为独立手机 Android 应用的产品边界；不把 03 歌词、03桌面或文件管理器源码并入本仓库。
2. 已记录首次安装主链：局域网优先发现 / 连接设备，用户选择组件（03桌面唯一必装且不可取消，03 歌词和文件管理器可选），随后由助手自动完成下载页解析、ZIP 下载与校验、APK 解压与校验、ZIP 删除、非流式推送、安装、一次统一综合命令授权、仅启动 03 桌面和可用性验证，成功后转入维护态。
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
8. 真实交互 smoke 已完成：选择页可分别取消 03 歌词和文件管理器，03桌面保持不可取消；点击“开始安装”进入安装阶段；维护态滚动后全部三组动作可见，断线态显示“重新连接”。
9. 指定手机上的 instrumentation 已通过 `2/2`：生产入口和 Debug 维护场景均到达 resumed 状态。测试 APK 显式加入 `androidx.test:runner:1.5.2`，不再出现运行器缺失导致的假失败。
10. F0 只证明手机工程、状态投影、视觉壳、动效和启动可运行；真实局域网发现、组件下载 / 解压 / 哈希、ADB 上传安装、授权回读、车机可用性验证、Cloud 发布链和正式签名仍未施工。
11. F0 基座已在提交 `948f51d` 固化；提交前完成暂存区差异检查，未包含本机上下文、设备地址、截图或构建产物。

## 2026-08-18 F1 安装会话与页面接线

1. 已新增 `app/src/main/kotlin/com/tcrrry/helper/domain/session/InstallationSession.kt` 与 `InstallationSessionCommand.kt`；文件头、包名和编码沿用 `InstallationSessionSnapshot.kt`，没有新增外部依赖或业务权限。
2. `InstallationSession` 现在是唯一可变状态 owner，暴露 `StateFlow<InstallationSessionSnapshot>`；快照新增会话代次、修订号、事件序号、可恢复检查点和结构化证据，旧会话事件、重复事件和未知事件均不能覆盖较新状态。
3. 已实现并由单测覆盖：显式开始发现、设备去重、未确认设备阻断、03桌面唯一必装锁定、03 歌词 / 文件管理器可选切换、元数据完整性门禁，以及 `SELECTION_CONFIRMED` 后严格经过获取 / 下载 / 归档校验 / 解压 / 产物校验 / 安装 / 综合命令授权 / 设备验证阶段。
4. 取消、断线和可恢复错误进入 `PAUSED` 并保存检查点；断线恢复必须接收带 `CONFIRMED` 设备身份的结构化重连事件。前置结果缺失、校验失败、未知事件和成功证据不足均 fail closed。
5. 只有安装、授权、可用性三类证据以及前置产物校验全部成立时才进入 `SUCCEEDED`；成功后的维护入口仍由同一会话接收，不建立第二套 Debug 或 UI 状态机。
6. `MainActivity` 已改为创建会话并用生命周期感知方式收集快照；`InstallUiStateMapper` 仍是唯一领域到 UI 投影，并同步补齐元数据 / 设备确认的启动按钮门禁。
7. `DebugScenarioActivity` 已改为只发送 fake command / adapter event；`flow`、暂停、失败、成功和维护场景均通过生产 `InstallationSession` 推进，不再在 Debug 内复制 `snapshot.copy` reducer。
8. 本轮本机验证通过：`./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`；lint 无错误，仅保留 F0 已锁定依赖的版本提示。
9. 指定手机 `SM-F946B` 上完成 Debug / AndroidTest APK 保留数据覆盖安装；`InstallAppActivitySmokeTest` instrumentation `2/2` 通过。真实运行检查确认生产入口点击“重新查找”进入“正在查找车机”，Debug `flow` 到达成功结果，暂停 / 失败场景显示对应恢复文案；未执行车机安装、网络下载或 ADB 业务动作。
10. 本轮未推送、未发布；F1 代码、测试和文档改动已由本次交接提交统一固化，未写入本机上下文、设备地址、截图或构建产物。

## 2026-08-18 F1 验收决策与 F2 启动

1. 用户明确跳过 F1 的 Debug 场景与页面人工验收；该项记录为“用户主动不测”，不计作人工通过，也不回退或降低 F1 已有的自动化断言。
2. F1 已完成本机低成本验证和指定手机基础 smoke：领域单测、UI 映射单测、Lint、Debug / AndroidTest 构建、instrumentation `2/2` 以及生产入口 / Debug 场景运行检查均已通过；真实网络、WebView、局域网、ADB 业务操作、车机安装和授权仍未执行。
3. 当前施工阶段切换为 F2“下载与发布清单”。F2 只负责把可信组件清单、隐藏 Android System WebView 下载回调、应用内 ZIP 下载、归档 / APK 完整性验证和固定备用源策略接入现有 `InstallationSession`，不施工 LAN、ADB、车机安装或授权。
4. F2 的外部结果必须通过结构化端口 / adapter event 注入 `InstallationSession`；不得把 URL 解析、下载协议、ZIP 文件路径或错误分支写进 Compose 页面，也不得另建状态机。
5. F2 交接时必须保留真实能力的待验证标记：在用户进行真实网络 / ZIP / WebView 验收前，不把 Debug fixture、单元测试或模拟下载写成真实源可用。

## 2026-08-19 F2 下载与发布清单施工

1. 已按用户要求把本轮验证口径固化为“最简自动化 + 用户人工主测”：更新根 `AGENTS.md`、`task-closeout` Skill、验证规则、验证矩阵和 F2 计划；本轮不执行设备、外部浏览器、真实 WebView、真实网络或运行级 smoke。
2. 已冻结 `ArtifactManifest` schema：组件身份、发布版本、Android / 安装助手兼容范围、`archiveFormat=zip`、ZIP 文件名 / 大小 / SHA-256、唯一 `apkEntryName`、APK 大小 / SHA-256、包名、APK 版本和证书摘要，以及固定三源。签名 envelope 固定为 `SignedCatalogEnvelope`，先验签再严格 JSON 解码。
3. 已落地 `CloudReleaseCatalogAdapter`、`ReleaseSourcePolicy`、`LanzouWebSourceAdapter`、隐藏 `AndroidLanzouWebViewHost`、`ArtifactDownloader`、`ArtifactCache`、`ArchiveIdentityVerifier`、`ArtifactArchiveExtractor`、`ArtifactIdentityVerifier` 和 `ArtifactPreparationCoordinator`；新增唯一 `android.permission.INTERNET`，并关闭明文流量，未新增存储、ADB、无障碍或定位权限。
4. 隐藏 WebView 保持 Android System WebView 默认手机端标识，页面不挂载到视图层级、不接收触摸 / 焦点、不暴露 JavaScript bridge；下载上下文仅在内存中传递。下载使用应用私有 `.zip.part`，同一清单 / 来源类型才允许 Range 恢复，元数据不含短时 URL、Cookie 或 Referer。
5. ZIP 必须先通过大小和 SHA-256；解压只接受清单指定的唯一根目录 APK，拒绝路径穿越、嵌套目录、多个 APK、额外文件、CRC / ZIP 结构异常和输出超限。APK 大小、SHA-256、包名、版本、证书全部通过后原子转正并立即删除 ZIP；失败清理 ZIP、部分 APK 和无效 APK。
6. F1 会话已扩展为接收清单、来源选择 / 失败、归档 / 解压 / 产物证据；`ArtifactsVerified` 成功后停留在 `VERIFYING_ARTIFACTS`，F2 不发送 `InstallationStarted`、安装、授权或车机可用性事件。Compose 仍只走 `InstallationSessionSnapshot -> InstallUiStateMapper -> InstallApp`。
7. 本机自动化已通过：F2 领域 / 适配器单测 21 项与既有 F1 / UI 单测合计 41 项全部通过；覆盖清单签名 / 字段、来源顺序、HTML 假响应、取消与恢复、ZIP / APK 反例、缓存清理、来源切换和会话阶段门禁。另通过 `:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`node scripts/check-project-docs.mjs`、`node scripts/check-skills.mjs`、`git diff --check` 和本机环境快检。
8. 已只读核对 Cloud 仓库：当前仅 TileLauncher 有安装包 release profile，03歌词 / 03桌面只有产品或商业配置，不存在 03helper Android profile、组件 ZIP 对象、清单公钥或发布脚本。本轮没有修改 Cloud。真实 Android System WebView 隐藏回调、真实蓝奏云 ZIP、切网 / Range 续传、R2 / GitHub 对象一致性和手机画面主测仍未执行；这些已整理到 `docs/testing/验证矩阵.md` 的 F2 人工用例，不能用 fixture 结果替代。
9. 用户复核后确认：Cloud profile、公钥和组件 ZIP 不应由测试用户自行获取；`docs/testing/验证矩阵.md` 已改为由项目 / Cloud 发布方提供候选，资料缺失时记录外部发布前置阻断。清单签名 / 字段反例继续由确定性自动化测试覆盖，不要求在手机上手工伪造。
10. 使用本机配置中的显式手机 serial 做了只读包检查：设备在线，原先安装的是 `com.tcrrry.helper` 的 `versionName=0.1.0`、`versionCode=1` 旧 Debug 包，最后更新时间为 `2026-08-18 23:34:24`；该包请求权限中没有 F2 新增的 `android.permission.INTERNET`，因此不是当前 F2 构建。
11. 当前生产 `MainActivity` 只创建并驱动 `InstallationSession`，仓库尚无生产 LAN / ADB discovery adapter；因此“查找车机”无真实发现结果是 F3 尚未施工的预期阻断，与另一 ADB 助手是否连接车机无关。
12. 用户将本轮真实来源范围收窄为蓝奏云单组件 ZIP 主源；R2 / GitHub Releases 尚未上传组件 APK / ZIP，已从当前人工前置和失败判定中移除，固定备用顺序继续只由自动化 fixture 覆盖。
13. 经用户明确要求，使用显式手机 serial 对当前 F2 Debug APK 执行保留数据覆盖安装，ADB 返回 `Success`；安装后包仍为 `0.1.0 (1)`、Debug、`INTERNET` 已声明并授予。未启动应用，等待用户进行主源人工测试。
14. 进一步核对生产接线：`MainActivity` 当前只创建 `InstallationSession`，`ArtifactCatalogSessionAdapter` 与 `ArtifactPreparationCoordinator` 尚未在生产入口组装；在 Cloud Android profile / 公钥和组件映射具备前，当前 APK 不能从主界面触发真实蓝奏云下载。该阻断独立于 R2 / GitHub 尚未上传对象。
15. 用户实测反馈：手机安装助手查找不到车机，但电脑上的既有 ADB 助手可以找到并连接。源码与运行边界确认这不是两个工具争抢连接；电脑 ADB 会话不会被手机应用复用，当前安装助手也没有发起 LAN / ADB discovery 请求，因此 F2 后续真实来源测试会在生产入口前置处停止。

## 当前未完成事项

1. 由项目 / Cloud 发布方提供并接入可验证的 Android profile、公钥、组件映射、ZIP / APK 身份和正式签名资料；资料缺失时客户端继续保持 fail closed。
2. 真实 Android System WebView 蓝奏云回调、真实 ZIP 和正常三组件主链已经通过；仍需补做切网 / Range 续传、主源失败清理以及 R2 / GitHub Releases 对象上传后的回滚链验证。
3. 真实 `S56_HQX` 正常安装、统一授权和 03 桌面启动可用性已经通过；仍需补做断线恢复，并用独立设备验证异常和破坏性动作门禁。
4. F4 常用维护动作已完成应用层接线（检查更新、保留数据重装、修复授权、受控应用状态、缓存清理和脱敏诊断）；卸载、清除数据、降级、重启和其它高级动作仍未施工，真实目标车机维护主测待执行。
5. 为 03 歌词、03桌面和文件管理器取得可审计的包身份、兼容范围、发布签名和合规材料。

## F2 交接入口（历史记录，已由 F3 接线取代）

F2 Debug APK 已按用户授权安装到指定手机。该段记录的是 F2 交接当时的状态：生产入口尚未组装清单 / 产物协调器，且 Cloud Android profile、公钥和组件映射仍未具备。随后 F3 已完成生产 LAN / ADB、清单和产物协调器接线；当前真实手测仍只在发布资料具备后验证蓝奏云主源，R2 / GitHub Releases 对象尚未具备，不纳入本轮测试。

## 2026-08-19 F2 收尾与下轮交接

1. 用户决定结束 F2 的本轮人工测试，不再用“查找车机”结果反复回归；本轮没有把真实 WebView、真实蓝奏云 ZIP 或生产发布链写成通过。
2. F2 已提交的本地能力包括：签名清单 schema / 验签、固定来源策略、隐藏 Android System WebView 适配器、私有 `.zip.part` 下载与同源恢复、ZIP / APK 完整性校验、受控解压、失败清理和结构化 `InstallationSession` 事件。
3. 下轮施工开始前必须先补齐生产可测入口：接入真实 `DeviceDiscovery` / `DeviceTransport`（F3 owner），在生产入口组装 `ArtifactCatalogSessionAdapter` 与 `ArtifactPreparationCoordinator`，并接入项目 / Cloud 提供的 Android profile、公钥和组件映射。不得把三个蓝奏云 fixture 地址按顺序猜成产品身份。
4. 在 R2 / GitHub Releases 对象上传前，下轮真实来源仍只测蓝奏云主源；备用源只保留自动化 fixture，不要求用户准备或验证不存在的对象。
5. 当时的验证口径保持“最简自动化 + 用户人工主测”：优先运行语法 / 编译、直接相关单测和文档护栏；F2 未追加设备、浏览器或真实网络 smoke。该口径不应被解释为跳过 Debug 产物交付或覆盖安装。

## 2026-08-19 验证工作流纠偏

1. 用户明确补充交付要求：复杂真实流程由用户主测，但施工完成后必须先构建并保留数据覆盖安装最新 Debug APK，提供可测试前置；不能因“最简自动化”连 APK 都不安装。
2. 工作流已统一为：本机语法 / 编译与直接单测 -> Debug 构建 -> 显式测试设备保留数据覆盖安装 -> 仅执行已有、稳定、单条不超过 5 分钟且无破坏性动作 / 未具备外部前置的最小 smoke -> 复杂链路精确人工用例。
3. 本次纠偏只调整验证、收尾和交付规则，不改变 F2 已完成能力、Cloud 发布阻断或“R2 / GitHub 对象未上传”的事实；更新后的规则由根 `AGENTS.md`、`task-closeout`、验证规则、验证矩阵和本机开发环境文档共同承载。

## 2026-08-19 F3 设备发现与生产接线施工

1. 已复用 Apache-2.0 `dev.mobile:dadb:1.2.9` 作为 TCP ADB 协议客户端；项目侧只保留固定身份读取、LAN 范围 / 并发 / 超时 / 取消和结构化能力映射，不暴露库的任意 shell、安装、推送或 root API。
2. 已落地 `DeviceDiscovery` / `DeviceTransport`、IPv4 子网枚举、LAN ADB 发现、身份连接适配和 `DeviceDiscoverySessionAdapter`；设备候选经过会话去重，广播地址不进入扫描。真实目标车机发现仍待人工运行验证。
3. 已落地 `InstallerRuntime` 与 `ProductionInstallerRuntimeFactory`：生产 `MainActivity` 现在通过同一个 `InstallationSession` 组装发现、Cloud 清单适配和 F2 产物协调器；清单加载与产物事件共享同一会话代次和单调事件端口。
4. 当前生产 Cloud transport 仍明确使用 `UnavailableReleaseCatalogTransport`，因为 Cloud Android profile、公钥、组件映射和 ZIP / APK 身份资料尚未具备；选择车机后保持 `CONNECTED`，清单资料不可用并提供重新获取入口，不生成伪造生产清单，也不伪装成“安装未完成、已保留进度”。安装、授权、可用性验证仍未施工。
5. 已修正设备选择后的迟到发现事件隔离，并将清单声明的 Android 兼容范围与已确认设备 SDK / ADB 身份能力纳入开始安装前门禁。
6. 本轮直接相关自动化共 54 条单测全部通过（0 failures、0 errors）；新增运行时、前台入口、会话与 UI 投影回归共同锁定“资料不可用不伪装成可继续安装失败”及“连接页前台自动发现”；`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、项目文档检查、Skill 检查、差异空白检查和本机环境检查全部通过。
7. 已生成 `app/build/outputs/apk/debug/app-debug.apk`，并使用本机配置中的显式手机 serial 保留数据覆盖安装；生产 APK 与 AndroidTest APK 安装均返回 `Success`。包身份为 `com.tcrrry.helper`、Debug、`0.1.0 (1)`，本地签名证书摘要为 `2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27`。
8. 覆盖安装后执行现有 `InstallAppActivitySmokeTest`，生产入口和 Debug 维护场景 `2/2` 通过；未启动真实车机扫描、蓝奏云 WebView、真实网络下载、车机安装、授权或可用性验证。
9. 当前用户可主测前置：手机已安装上述 Debug 包；若要验证 F3 发现，把手机与目标车机放在同一局域网，车机保持现有 TCP `5555` 可达，然后在助手首屏点击“重新查找”。若发现设备，选择车机后因 Cloud Android profile 仍缺失而保持已连接并显示“暂时无法准备安装应用”，点击“重新获取”只重试清单加载；不应继续尝试组件安装。

## 2026-08-19 现场状态语义纠偏

1. 用户现场选择到车机后看到“安装未完成，已保留可继续的进度”。根因不是旧 APK 数据、缓存或电脑上的 ADB 助手抢连接，而是生产清单资料缺失事件被错误映射为通用 `FAILED`。
2. `InstallationSession` 现将 `CONNECTED` 阶段的 `CatalogFailed` 保持在 `CONNECTED`，保留结构化清单失败原因并清除安装检查点；页面使用既有“暂时无法准备安装应用 / 重新获取”入口，不再制造可继续安装的假象。
3. 其它阶段收到越序清单失败仍 fail closed；Cloud Android profile、公钥、组件映射和 ZIP / APK 身份资料仍是外部发布阻断，未由客户端伪造。

## 2026-08-19 前台自动发现修正

1. F3 当前真实人工边界已确认：本轮只验证手机能否发现并连接车机；连接成功后因 Cloud Android profile、公钥、组件映射和真实 ZIP / APK 身份缺失，第二步清单准备停止，不为制造测试路径伪造兼容资料或扩展临时分支。
2. 现场发现生产入口初始快照停在 `IDLE`，冷启动和回到前台都不会自动发起发现，用户只能手动点击“重新查找”。
3. `InstallerRuntime.onForeground()` 现由 `MainActivity.onStart()` 调用：连接页首次进入或从后台回前台时自动启动一次有界发现；发现进行中、已连接选择页、结果页和维护态不重复扫描，当前前台主动停止后不立即重启。
4. 新增应用层前台入口回归测试；本次代码变更后需重新构建并保留数据覆盖安装 Debug APK，再运行现有最小 instrumentation smoke。真实车机安装、蓝奏云、WebView 和清单资料仍不纳入本轮自动验证。

5. 指定测试手机恢复无线调试后，使用用户提供的配对端点和配对码完成 ADB 配对，再通过 mDNS 连接服务恢复为独立手机 serial；加入首帧预启动后的最新 Debug APK 与 AndroidTest APK 均已保留数据覆盖安装并返回 `Success`，安装后现有 instrumentation smoke `2/2` 通过。车机 serial 未用于手机测试。

## 2026-08-19 F3 连接语义与持久在线修正

1. 用户明确确认：点击设备行必须在该动作中完成真实车机连接；初始化、清单加载、下载和后续成功进入维护态期间继续持有同一连接，只有维护页主动断开、重新发起发现、进程结束或客观掉线时释放。
2. 已将原先“发现时短连接、点击后直接进入 `CONNECTED`”修正为两阶段主链：发现阶段仍使用 `DadbDeviceTransport` 做短探测；点击后进入 `CONNECTING`，由 `DadbDeviceConnectionFactory` 建立第二次真实 TCP ADB 握手并返回 `DeviceConnectionLease`；`DeviceConnectionConfirmed` 到达后才进入 `CONNECTED`。
3. `InstallerRuntime` 现在是连接租约唯一 owner：清单缺失仍保留 `CONNECTED` 和租约；回到前台只做一次固定身份健康检查，不做常驻高频轮询；维护页新增用户主动“断开车机”入口。
4. UI 已增加“正在连接车机”真实等待态和“已连接到 <车机名称>”证据文案；连接失败回到可重试连接态，不再用立即跳页掩盖握手结果。电脑上的其它 ADB 助手不作为连接证据，各客户端连接彼此独立。
5. 已补充领域、运行时、租约关闭、连接失败、健康检查边界和 UI 投影单测；当前直接相关单测共 `61` 条通过，`compileDebugKotlin`、`testDebugUnitTest`、`lintDebug`、`assembleDebug` 与 `assembleDebugAndroidTest` 均已通过。最新 Debug 构建、保留数据覆盖安装和 instrumentation smoke 已在本节后续收尾记录中完成。

## 2026-08-19 F3 连接语义收尾与可测试交付

1. 当前工作区重新通过 `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 和 `:app:assembleDebugAndroidTest`；直接相关单测共 `61` 条，0 failures、0 errors。
2. 最新 Debug APK 与 AndroidTest APK 已生成并核对 SHA-256：主包为 `fb22c9ba660d0e8a1ce0654b44c1a8978638210259d181f67c5521e847a2c763`，测试包为 `f56066697ff7dd1a435b77c30e8559d61744f3c4f9704fa6f87f4a5e21cef3a1`；包身份为 `com.tcrrry.helper`、Debug、`versionName=0.1.0`、`versionCode=1`。
3. 已使用显式测试手机 serial 对主包和 AndroidTest 包执行保留数据覆盖安装，系统均返回 `Success`；未清数据、卸载、降级、重启或对车机执行安装 / 写入。
4. 覆盖安装后运行既有 `:app:connectedDebugAndroidTest`，目标为 `SM-F946B` 测试手机，`InstallAppActivitySmokeTest` 共 `2/2` 通过，覆盖生产入口 resumed 与 Debug maintenance resumed。
5. 本轮真实车机扫描、第二次 ADB 握手、连接租约持续性、蓝奏云 Android System WebView、真实 ZIP / APK、安装、授权和可用性验证仍交给用户最小人工主测；Cloud Android profile、公钥、组件映射及真实 ZIP / APK 身份资料缺失仍是继续安装的外部阻断，详见 `docs/testing/验证矩阵.md`。

## 2026-08-19 instrumentation 收尾后的最终交付安装

1. 现场复核发现，`connectedDebugAndroidTest` 收尾会移除主包和测试包；因此“先覆盖安装、再运行 smoke”不能作为最终设备状态，测试手机在 smoke 后不再显示 `com.tcrrry.helper`。
2. 已将收尾规则统一修正为：允许先安装并运行短 smoke，但所有自动化结束后必须再次使用显式 serial 保留数据覆盖安装最新 Debug APK，并核对包身份、版本和 launcher activity。
3. 已在测试手机完成最终主包覆盖安装，系统返回 `Success`；核对结果为 `com.tcrrry.helper`、Debug、`0.1.0 (1)`、`android.permission.INTERNET` 已授予，`MainActivity` 可被 `MAIN` / `LAUNCHER` 解析。当前交付设备状态以这次最终安装为准，未再次运行会清理包的 instrumentation。

## 2026-08-19 手机应用 ADB 连接实证

1. 通过测试手机的无线 ADB 进行只读核对：点击发现的 `S56_HQX` 后，`com.tcrrry.helper` 进程所属 UID 的网络表出现到车机 TCP `5555` 的 `ESTABLISHED` 会话，车机端同时出现匹配的反向会话。
2. 手机界面同步显示“已连接到 S56_HQX”，随后因 Cloud Android profile 缺失停在“暂时无法准备安装应用”。这次证据证明的是手机应用自己的 Dadb ADB 租约，不是电脑上的另一个 ADB 助手连接；普通 `adb devices` 列表不能替代这项判断。
3. 该证据是当前时刻的连接快照；应用后续仍通过前台健康检查和客观断线事件更新状态，真实安装流程继续受 Cloud profile、公钥、组件映射和 ZIP / APK 身份资料阻断。

## 2026-08-20 F3 安装授权主链口径收敛

1. 产品口径已确认：03桌面是唯一核心且必装；03 歌词和文件管理器均为可选。Debug 签名 profile 已更新为 `android-real-debug-2026-08-20-v4`，清单按桌面优先排序，歌词的 `required` 字段为 `false`，并由新的本地 Debug ECDSA 信任根重新签发；Release 不读取该资料。
2. 安装阶段逐个执行 `pm install -r`，不启动任何目标应用；所有已选包安装完成后回读车机内实际 APK 的大小、SHA-256、包名、版本和证书。
3. 授权与启动阶段只调用一次固定、版本化、白名单综合命令：按已验证的组件 ID 授权已安装组件，缺少可选组件时记录跳过并失败闭环，最后只解析并启动 03桌面；03 歌词和文件管理器不启动。
4. 状态机、设备协调器、DADB 网关、Debug 场景、测试夹具和产品 / 架构 / 验证文档已同步上述不变量；`testDebugUnitTest` 当前 71 项全部通过。
5. 用户已明确授权：若真实车机已有同包名安装且保留数据覆盖安装因签名 / 降级冲突无法继续，可在本轮测试中只对三个已核验目标包名执行精确卸载；不得扩大为默认清理行为。真实车机安装、授权和可用性 smoke 待本轮 Debug APK 构建后执行。

## 2026-08-20 F3 三组件真实完整主链通过

1. 测试前只读核对确认三个精确目标包均不存在，保护包 `com.tcrrry.desktopcast` 仍存在。手机先停在结构化下载失败页；对应日志表明蓝奏页面在约 `4.4` 秒内未触发下载回调并返回 `lanzou_download_trigger_timeout`，安装助手进程未崩溃，车机没有进入安装或授权阶段。
2. `05:28:19` 从手机页面点击一次“重新尝试”后不再进行任何用户操作。三个隐藏 WebView 均自动捕获 `zip1.webgetstore.com` 的短时下载地址，三个响应都是 `200 application/octet-stream`；页面按“获取安装包 -> 检查安装包 -> 发送到车机 -> 正在授权 -> 检查是否可用”自动推进，没有外部浏览器、可见蓝奏页面或额外授权按钮。
3. 03桌面、03歌词和文件管理器分别在 `05:28:27`、`05:28:29`、`05:28:38` 完成首次安装；`05:28:51` 只出现一次 `shortcut_result completed`。从重试点击到统一命令完成约 `32.4` 秒，低于本轮 `120` 秒测试停止边界；该边界没有写入产品超时规则。
4. 手机最终自动进入“安装完成”，说明为“安装和授权已完成，请在车机开始使用。”，三个已选组件均显示“已安装 · 已完成授权 · 可用”。授权期间页面只显示“正在授权”和状态驱动进度，不要求用户点击；失败时保留“重新授权”作为恢复动作，不构成正常主链步骤。
5. 车机最终 APK 与签名 Debug profile 逐项一致：03桌面 `0.1.0 (1)`、`6615694` 字节、SHA-256 `21f2f432070cf356a2c99f64e415a6b41cba510eebd7673ee256e94c11e9ea75`；03歌词 `1.14-icar03 (114)`、`6173971` 字节、SHA-256 `839dd4403233d82025b6ad2429a3b914a1ab8810dd0c556262574698d8514c41`；文件管理器 `1.6.1-car175.1 (14)`、`31423255` 字节、SHA-256 `8f64699f6fb2bb4b5006b57e20e994b6946518eb749fa07fa4ff6a7c1726c1b4`。应用主链还完成了包名、版本和证书回读门禁。
6. 综合命令源码只有一个显式 `am start`，目标固定为 03桌面；03 桌面进程、前台 Overlay Service 和必要无障碍 Service 均可观察。03歌词的进程及两个 Service 是 Android 在通知监听和无障碍白名单回读成功后自动绑定产生，不是助手显式启动；文件管理器没有运行进程。保护包在测试后仍存在。
7. 整轮真实复测没有从电脑端手工注入综合授权命令，没有清数据、重启、触碰保护包、安装 Release、发布、提交或推送。

## 2026-08-20 F3 收尾验证与最终手机交付

1. 当前源码重新通过 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 和 `:app:assembleDebugAndroidTest`；单元测试报告为 `71` 项、`0` failures、`0` errors。`apksigner` 核对 Debug 主包的 APK Signature Scheme v2 为 `true`。
2. 使用当前显式手机 serial 运行既有 `:app:connectedDebugAndroidTest`，`InstallAppActivitySmokeTest` `2/2` 通过；测试框架随后移除了主包，符合既有收尾行为，未把该中间状态作为最终交付。
3. 文档、Skill、本机环境和差异检查均通过：`check-project-docs.mjs`、`check-skills.mjs`、`check-local-environment.mjs`、`git diff --check`。
4. 所有自动化结束后，使用最新 `app-debug.apk` 对手机执行一次保留数据覆盖安装，系统返回 `Success`。最终包为 `com.tcrrry.helper`、Debug、`0.1.0 (1)`，`android.permission.INTERNET` 已授予；`MAIN` / `LAUNCHER` 解析到 `com.tcrrry.helper/.MainActivity`。APK SHA-256 为 `dc0bec47aa202ddefd840608d1dc072265d2fc49c2bb5f06637a50186240e45f`。
5. 本轮不提交、不推送、不发布；未清数据、卸载、降级、重启手机或车机，也未从电脑端手工执行综合授权命令。剩余未覆盖项仅为切网 / Range / 断线恢复、R2 / GitHub 发布对象和正式 Release 资料，不影响本次 Debug 三组件正常主链目标。

## 2026-08-20 F4 维护态施工与收尾

1. 已将维护动作接入 `MaintenanceController` 与同一 `InstallationSession`：检查更新、保留数据重装、修复授权、管理已安装应用、安装文件管理器、启动受控组件、缓存清理和脱敏诊断均使用固定动作 ID 与结构化完成 / 失败事件；重装和安装文件管理器复用既有清单、下载、校验和安装主链。
2. 会话已增加维护动作串行门禁、断线失败收口和断线本地动作白名单；维护 UI 显示运行中 / 完成 / 失败反馈，车机断开时禁用车机动作，并在“管理已安装应用”成功后投影 03 桌面、03 歌词和文件管理器三项状态。
3. 已新增 `MaintenanceSessionStore`：使用应用私有文件、schema 版本、严格 JSON、原子替换、来源策略和固定包身份校验；只保存设备摘要、组件、安装 / 配置 / 可用证据、当前 / 候选清单和最近一次已完成或失败动作，不保存活动动作、Cookie、Referer、短时 URL 或 shell 输出。损坏、过大、过期或不受信记录加载为无快照；有效记录冷启动为 `MAINTENANCE + DISCONNECTED`。
4. 维护重连已接入同一发现 / 连接租约主链：从维护态发起有界发现时保留原清单、选择和证据；停止、无设备、设备不一致或握手失败回到维护断线态；确认同一设备后恢复维护态，不重新加载首次安装清单。
5. 自动化验证通过：`91` 项 JVM 单测、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`check-local-environment.mjs`、`check-project-docs.mjs`、`check-skills.mjs` 和 `git diff --check` 均通过。
6. 使用显式测试手机完成主包和 AndroidTest 包保留数据覆盖安装；随后运行既有 `connectedDebugAndroidTest`，生产入口与 Debug 维护场景 `2/2` 通过。测试框架移除目标包后，已再次覆盖安装最新 Debug 主包并核对 `com.tcrrry.helper`、Debug、`0.1.0 (1)`、`INTERNET` 已授予以及 `MainActivity` 的 `MAIN/LAUNCHER` 入口。最终 Debug APK SHA-256 为 `9eb6a842b393220e081ed3eca11440b01956efa116f56802ba1fdf13bc48bb2f`，AndroidTest APK SHA-256 为 `f56066697ff7dd1a435b77c30e8559d61744f3c4f9704fa6f87f4a5e21cef3a1`。
7. 本轮未提交、未推送、未发布，未清数据、卸载、降级或重启设备，也未对真实车机执行维护写入。真实目标车机的检查更新、重装、修复授权、应用状态、断线重连和冷启动人工主测仍待执行；卸载、清除数据、降级、重启和任意 shell 仍不在 V1 主链，正式 Cloud / Release 资料阻断保持不变。

## 2026-08-21 根文件夹分发契约与安装恢复边界

1. 用户确认采用单一蓝奏密码根文件夹作为当前版本真值：Cloud 只需后台配置根文件夹地址和密码，文件夹内固定放 `03desktop-debug.zip`、`03lyrics-debug.zip` 和 `fossify-file-manager-car-debug.zip`；人工替换 ZIP 后，客户端下一次检查更新重新枚举目录并从 APK 动态读取版本、大小和 SHA-256。
2. 已将当前产品、架构、安全、计划和验证文档的现行口径切换为签名 `android-config` 控制面：HTTPS 接口返回 detached-signature envelope，密码只运行时驻留内存；未知、缺失、重复或额外文件、签名失败、过期或包身份不符时整次目录拒绝。此前每组件无密码分享页和 R2 / GitHub 自动备用仅保留为历史方案或未来受控扩展。
3. 客户端动态目录主链已落地为 `CloudInstallerDistributionConfigAdapter` -> `LanzouFolderSourceAdapter` -> `FolderArtifactCatalogAdapter` -> 原有安装 / 维护会话；根目录只作为发现和版本控制面，短时 ZIP URL、Cookie、Referer 和密码不进入持久状态。
4. 修正安装重连边界：`CONNECTED + checkpoint` 现在与其它可恢复安装阶段统一走安装重连，锁屏 / ADB 短暂断开后重新确认同一车机可恢复到原 `CONNECTED` 检查点，不再退回普通初始化发现或出现无响应的继续按钮。新增领域回归用例已通过。
5. 本轮新增代码后的完整验证已完成：`testDebugUnitTest` 共 `96` 项通过，`compileDebugKotlin`、`compileReleaseKotlin`、`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 均通过；`check-project-docs.mjs`、`check-skills.mjs`、`check-local-environment.mjs` 和 `git diff --check` 均通过。
6. 已在显式测试手机 `SM-F946B` 上先安装主包和 AndroidTest 包并运行既有 `connectedDebugAndroidTest`，生产入口与 Debug 维护场景 `2/2` 通过；测试框架结束后再次覆盖安装最新 Debug 主包。最终包身份为 `com.tcrrry.helper`、Debug、`versionName=0.1.0`、`versionCode=1`，Launcher 为 `MainActivity`，APK SHA-256 为 `575511baf998b2dab7d0dce3d8f3c7fadcbde46e4d75b80281ac42fffa243f79`，系统返回 `Success`。
7. 本轮未提交、未推送、未发布；未清数据、卸载、降级或重启设备，也未对真实车机执行维护写入。真实 Cloud staging 配置 / 根文件夹替换、切网与断线恢复的人工主测仍待执行；正式 Release 资料阻断保持不变。

## 2026-08-21 Debug 临时根文件夹旁路

1. 因 Cloud 接口仍在开发，Debug 构建临时跳过云端配置，固定使用 `https://wwatl.lanzouw.com/b0fqlrcyb`；密码只从被 Git 忽略的本机 `local.properties` 注入，不进入源码、提交或日志。Release 构建和正式 Cloud 主链不受影响。
2. 旁路只替换分发配置来源，仍复用密码根文件夹 WebView、三个 ZIP 枚举、下载、ZIP / APK 身份校验和原有安装状态机；不新增安装专用流程。
3. 本轮 Debug 构建、96 项单测、编译、Lint 和 `connectedDebugAndroidTest` 的 `2/2` smoke 已通过，并已覆盖安装测试手机。真实根文件夹请求尚未在 UI 中完成，因为测试手机当前处于锁屏状态，保留为用户解锁后的最小人工验证。

## 2026-08-21 维护二级页面与解锁快速重连

1. 用户确认产品名改为 `03车机助手`，安装成功主按钮改为 `完成！`；资源文案、Debug / Release 合并清单显示名称和 V1 成功结果表已同步。
2. 维护首页的九个功能入口现在统一由 `InstallApp` 持有二级页路由：普通维护动作向左进入独立动作页，顶部返回 / 系统返回向右回到首页；目标车机、影响范围、运行中、结果和下一步均在动作页呈现，不再把反馈插入维护首页顶部。重新安装和安装文件管理器离开维护态进入安装主链时仍保留同一动作页路由和横向转场；安装成功点击“完成！”会清掉动作路由并回到维护首页。
3. 锁屏断 ADB 不再被当作必须持续保持的产品承诺。运行时保留上一次已确认的车机端点；回到前台或健康检查发现断线时，先对该端点做一次快速身份握手，失败才回退到有界局域网发现；用户主动点击“断开车机连接”后不自动拉回。
4. 通过测试手机只读核对确认第三方 `com.reathin.adbassist` 没有注册 Android `Service`、前台服务或 `WAKE_LOCK` 权限，属于 Activity / 进程持有连接模式，不能直接照搬成常驻服务。
5. 本轮代码验证已通过：`testDebugUnitTest` 共 `97` 项、`compileReleaseKotlin`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、`check-project-docs.mjs`、`check-skills.mjs` 和 `git diff --check`。重连态已加入持续旋转的进度环，动作反馈进入 / 退出使用淡入淡出，二级页结果区明确展示下一步。最新 Debug APK `com.tcrrry.helper` / `0.1.0 (1)` 的 SHA-256 为 `203cbd76765e2e888ff5cc18fe01313fc1ba11401c5e39d245e728c22e9d7f93`，资源标签为 `03车机助手`。
6. `MainActivity` 仅在 `onResume` 进入前台恢复入口，覆盖首次进入和解锁回前台，避免同一生命周期重复触发发现 / 重连。既有 `connectedDebugAndroidTest` 在本轮代码改动前曾以 `2/2` 通过；本轮重跑时测试手机仍可 ping 但无线 ADB TLS 端口返回 `connection refused`，未把这次设备缺失误报为测试失败。最新 APK 尚未能在该次设备阻断后覆盖安装，待无线调试恢复后只需执行最终覆盖安装和同一 `2/2` smoke。
7. 收尾复核补齐维护二级页返回时的向右转场，并在动作运行中保留当前页面以持续展示状态；改动后的 97 项单测、Debug / Release 编译、Lint、APK 构建和文档护栏均重新通过。

## 2026-08-22 收尾复核

1. 新增“已确认端点握手失败后回退一次有界发现”的运行时回归用例后，重新执行 `testDebugUnitTest`、Debug / Release 编译、`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest`；98 项单测为 0 failures / 0 errors，全部构建成功。
2. 当前 Debug APK 已核对为 `com.tcrrry.helper`、`0.1.0 (1)`、显示名 `03车机助手`，APK Signature Scheme v2 验证通过；主包 SHA-256 为 `3905405b2494abad7c6ac4a0d78e1ff285e19af4d7fbd7c2edbc5ef2b3889526`，AndroidTest 包 SHA-256 为 `08cb79ccd0fbc59e9f3fc709c6d0f07d7b593b21506a7c14df68772a0a43ac0d`。
3. 无线 ADB 恢复后，使用本机上下文指定的 `SM-F946B` 测试手机完成既有 `InstallAppActivitySmokeTest`；生产入口和 Debug 维护场景 `2/2` 通过。Gradle 入口仍会读取其它在线设备的只读属性，因此后续应继续收紧测试设备隔离；本轮测试结果只报告测试手机，且只读核对确认车机没有安装 `com.tcrrry.helper`。
4. smoke 结束后已再次对测试手机保留数据覆盖安装最新 Debug 主包，系统返回 `Success`；最终包身份为 `com.tcrrry.helper`、Debug、`0.1.0 (1)`，`MAIN` / `LAUNCHER` 解析到 `com.tcrrry.helper/.MainActivity`。本轮未清数据、卸载、降级、重启、提交、推送或对车机执行维护写入。
5. 待用户最小人工主测：真实 Cloud 根文件夹下载与安装、维护态九个入口逐页进入 / 返回、锁屏后解锁自动快速重连、以及真实车机上的检查更新、重装、修复授权和受控应用管理。任一动作出现意外跳回首页、静态状态突变、连接未恢复或车机写入范围异常时停止该条并保留当前界面与时间点。

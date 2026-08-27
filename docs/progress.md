# 03helper 进度

## 2026-08-25 已安装身份与图标缓存收口

1. 复核发现：维护页按实时库存显示“已安装”并隐藏勾选框后，若车机只能报告包存在而没有 `versionCode`，可选组件可能同时被排除在内部批次之外，导致 UI 真值与安全复用条件不一致。
2. 已将该判定收回 `InstallationSession` 与 `DadbCommandGateway`：只有实时库存 `READY` 且包名 / `versionCode` 与受信 manifest 完全匹配的组件才跳过准备和设备写入；身份不完整或旧版的已安装可选组件仍保持不可勾选，但自动进入 APK 核验与安装批次。
3. 持久化 Logo 只有在包名、发布者证书、版本和 APK 摘要完整匹配时才命中；03 歌词内置 Logo 已逐字节替换为当前 APK 的 `ic_launcher_art.png`（SHA-256 `cc38255660f381d79957b1fd0b6b26e98e8f0f906af64bd2e2d2dd14200dc119`）。新增设备回归断言覆盖旧版安装路径；JDK17 下全量 JVM 单测共 `180` 项（0 failures / 0 errors / 0 skipped）。
4. 本轮最终 Debug APK 为 `com.ninepointnine.helper`、`0.1.0 (1)`、入口 `.MainActivity`，v2 签名有效，主包 SHA-256 为 `390e69c51982a59ca1f4d33084658d06a8b7755f159a91b838da92c7f6f31597`，AndroidTest APK SHA-256 为 `e7d46eff4e1930d9e7a297398e50b63c5615eec303af2916deaae2ae34fc0f5d`；显式测试手机 instrumentation smoke `2/2` 通过并已再次保留数据覆盖安装。
5. 目标车机 `192.168.0.203:5555` 当前离线，因此真实车机混合库存、补缺安装和“全部更新”覆盖安装仍是最小人工主测；本轮未对车机写入，未提交、未推送、未发布。
6. 共享 03 APP 登记 Guard 仅因当前工作树尚未提交、HEAD 与登记快照不一致而阻断；未修改共享登记库，也未把未提交代码标记为已发布产物。

## 2026-08-25 安装缺失项逻辑最终验证

1. 已按会话唯一安装策略收口：维护安装页把车机实时库存中的应用只显示为“已安装”，不加入本次默认安装集合；只有未安装应用进入清单准备、推送和 `pm install -r`。已安装应用仍执行车机 APK 路径、包名和发布者证书回读，库存读取失败时在任何设备写入前 fail closed；“全部更新”继续使用显式覆盖安装策略。
2. 指定 JDK17 强制重跑 `:app:testDebugUnitTest --rerun-tasks`，180 项测试全部通过；Debug / AndroidTest Kotlin 编译、Lint、Debug APK 与 AndroidTest APK 构建，以及项目文档、Skills、本机环境和 `git diff --check` 均通过。
3. 使用显式测试手机 serial `adb-RFCX412AN1X-gWfMRD (2)._adb-tls-connect._tcp` 直接运行 `InstallAppActivitySmokeTest`，2/2 通过；随后再次保留数据覆盖安装最新 Debug 主包并启动核对。最终包为 `com.ninepointnine.helper`、`0.1.0 (1)`、入口 `.MainActivity`，v2 签名有效，主包 SHA-256 为 `688449edd7bef3197183f7640ff29d9ae206e47c98def7ebd154fed79aaa4215`，AndroidTest APK SHA-256 为 `e7d46eff4e1930d9e7a297398e50b63c5615eec303af2916deaae2ae34fc0f5d`。
4. 目标车机 `192.168.0.203:5555` 当前离线，因此真实车机混合库存、补缺安装和“全部更新”覆盖安装仍是最小人工主测；本轮未对车机写入，未提交、未推送、未发布。

## 2026-08-25 维护安装缺失项分流施工（已完成）

1. 已将安装意图收敛为会话唯一的 `InstallationStrategy`：首次安装与维护“安装应用”使用 `INSTALL_MISSING_ONLY`，维护“全部更新”使用 `REINSTALL_SELECTED`，并随检查点恢复。
2. 维护安装选择页现在只把未安装的必需项放入默认集合；已安装项仅显示状态。已安装 03 桌面仍作为授权前置和最终证据的一部分，缺失的其它组件可以继续安装。
3. `DadbCommandGateway` 在补缺策略下先读取一次可信包库存：已安装包跳过推送和 `pm install -r`，仍执行车机 APK 身份回读；库存不可判定时 fail closed。设备协调器与生产运行时已接入该策略，保留“全部更新”的覆盖安装语义。
4. 已补充会话选择、策略恢复、运行时传递、混合库存设备写入分流、历史库存隔离和重装回归测试；JDK17 下 `:app:testDebugUnitTest` 共 174 项（0 failures / 0 errors / 0 skipped），`:app:compileDebugKotlin`、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、项目文档 / Skill / 本机环境检查和 `git diff --check` 均通过。
5. 最新 Debug APK 为 `com.ninepointnine.helper`、版本 `0.1.0 (1)`；在显式测试手机 serial 上完成主包与 AndroidTest 包保留数据覆盖安装，使用单一 serial 直接 runner 执行既有 `InstallAppActivitySmokeTest` 2/2 通过。instrumentation 收尾后再次覆盖安装主包并核对入口 `.MainActivity`，最终 APK SHA-256 为 `6526990a5184e11bd4b81d079f1a176f9434b75aeb65404e97b9d534c2e7de92`。
6. Gradle connected task 发现同一手机的两个 mDNS serial 并行执行，产生重复设备 runner 崩溃；该次设备选择异常不作为业务测试结果，单一 serial 直接 runner 已通过并完成最终主包覆盖安装。真实目标车机当前未在线，车机上的混合库存、补缺安装和“全部更新”覆盖安装仍保留为用户人工主测；本轮未对车机写入，不提交、不推送、不发布。

## 2026-08-24 维护与云端配置施工（进行中）

1. 已在基线提交 `13ad8ca` 后继续施工：Cloud 清单允许携带 `03helper` 自身包，首次安装会在会话边界和蓝奏云选择层过滤自身；维护更新保留自身清单用于版本比对，并对蓝奏云构建结果执行自身包名强校验。
2. 已修复维护态手动断开后的错误重连代次；返回按钮改为无框图标，二级页标题统一为图标 + 标题，首页滚动位置由 `LazyListState` 保留，移除旧的重新安装 / 启动组件 / 一键清理 / 诊断入口。
3. 已接入检查更新双板块、授权检查状态、受控应用启动 / 强停 / 卸载 / 详情动作、安装应用选择页和安装后私有缓存自动清理；新增页面均沿 `InstallationSession` / `MaintenanceController` / `InstallUiStateMapper` 主链。
4. 本阶段已通过 `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:lintDebug` 和 `git diff --check`。真实手机页面 smoke、显式 Debug APK 覆盖安装和真实车机维护动作仍待收尾；自身 APK 的系统级安装端口尚未接入，不能宣称已完成自身覆盖安装。

## 2026-08-24 Android 身份、版本与签名规则固化

1. 已将五个 Android source-set 的源码根和所有 Kotlin / 测试包迁移为 `com.ninepointnine.helper`，Gradle `namespace` / `applicationId` 同步更新；历史 `com.tcrrry.helper` 不再作为构建身份。
2. 已新增根目录 `release-version.properties`，Release 默认版本为 `1.0.0` / `versionCode=1`；`scripts/bump-release-version.mjs` 支持 `--check`、未指定时 patch 自动递增和明确 `--version` 覆盖，Debug / staging 继续固定 `0.1.0` / `versionCode=1`。
3. 已在仓库外本机受控目录建立 03helper 专用 staging / production RSA 4096 签名材料，并接入 Gradle 显式属性入口；公开证书摘要分别为 `aca4f178fea11ccc97a1373c8aa5345b274a3a783398929a9340a79ee83663af` 与 `31ca80dd21a5208eaabd5f3e1440a3db2f7dc79122e03eaa6ba01730fb31f18b`。仓库只保留示例字段与读取逻辑，未写入 JKS、口令或私钥。Release 缺 production 材料、staging 缺 staging 材料时均设计为 fail closed。
4. 本轮验证已通过：JDK 17 环境下 `:app:compileDebugKotlin`、`testDebugUnitTest`（138 项）、`lintDebug`、默认 Debug / AndroidTest 构建、staging Debug 签名构建和 production Release 签名构建均成功；版本脚本、项目文档、Skill、本机环境和 `git diff --check` 均通过。两种签名 APK 均核对为单 signer、RSA 4096、APK v2 有效。
5. 云端接线交接需新增 `productId=03helper`、包名 `com.ninepointnine.helper`、staging / production 公开证书摘要和许可证下发策略；Cloud 不接收或保存私钥，只在临时构建目录注入对应环境签名材料。

## 2026-08-23 连接后快速清单与逐应用安装进度

1. 已将首次连接后的目录主链拆为两段：`FolderArtifactCatalogAdapter.loadSelection()` 只验签配置、读取一次蓝奏根目录并发布轻量应用列表；连接阶段不下载 ZIP、不解析 APK。
2. 已将用户确认文案改为“开始下载并安装”，确认后仅针对已选 APP 生成真实清单并进入原有下载、校验、安装、授权和可用性主链；选中清单准备使用最多 2 个并发任务。
3. 已把逐应用 `ComponentProgressUpdated` 事件接入会话与进度页：下载阶段显示真实字节进度，校验、发送、授权和可用性阶段显示对应应用的独立状态与进度条。
4. 选择行不再渲染兼容性说明“适用于当前车机”；兼容性仍由会话内部门禁校验。产品、计划和验证文档已同步当前物理边界；带日期的历史章节保留原样。
5. 本轮直接相关验证已完成：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（131 项）、`:app:lintDebug` 和 `:app:assembleDebug` 均通过；新增缓存复用回归用例确认同一选择动作不会重复解析 / 下载 ZIP。
6. 最新 Debug APK 为 `com.tcrrry.helper` / `0.1.0 (1)` / `MainActivity`，SHA-256 为 `f1800d666d75badacb5c4f57f8c508c7a08f7213d9314b27c44a603282f29539`；已在指定测试手机 `SM-F946B` 保留数据覆盖安装并启动，系统返回 `Success` / `Status: ok`。新安卓机已由主机完成配对并切到测试用 TCP ADB，但因其 Android 11 安全 ADB 需要客户端密钥，当前助手仍未把主机连接误报为应用连接，目标机未安装助手 APK。

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

## 2026-08-23 选择页状态与展示收窄

1. 实机日志确认连接成功后的 `CONNECTED` 会话会先异步读取 V3 配置、蓝奏根目录和各组件 ZIP；此前空组件列表直接被投影成“暂时无法准备安装应用”，因此用户会在目录准备期间看到约十几秒的误导性失败文案。客户端接口连续请求稳定返回，问题是 UI 把“准备中”和“失败”合并，不是云端协议字段不一致。
2. `InstallUiState.Selection` 新增准备中投影；选择页在目录未完成时显示“正在准备安装应用”和进行中指示，只有带失败原因的空列表才显示“重新获取”。
3. 按用户原始范围收窄首次安装组件行：保留本地 APP logo、名称、必装 / 可选、版本、体积和兼容范围；云端 `description`、目录状态、单项错误和连接确认行不再渲染在该框中，但仍保留在内部协议、校验和结果证据链。
4. 已通过 `:app:testDebugUnitTest` 与 `:app:assembleDebug`；最终 `app-debug.apk`（SHA-256 `295f998b0b6ad30edb7f90fb35f321358e2f06ed793dfe3314bc4d16089ce2a1`）已按本机显式 serial 保留数据覆盖安装，包身份 `com.tcrrry.helper` / `0.1.0 (1)` 与 `MainActivity` 启动入口核对通过，启动后进程保持运行。未提交、未推送、未发布。

## 当前未完成事项

1. 由项目 / Cloud 发布方提供并接入可验证的 Android profile、公钥、组件映射、ZIP / APK 身份和正式签名资料；资料缺失时客户端继续保持 fail closed。
2. 2026-08-20 的三组件蓝奏云 / 车机主链是带日期的历史验真事实；当前 V3 staging 配置、根目录和动态 APP 清单已完成客户端选择页验证，真实 WebView 下载、ZIP / APK 校验、切网 / Range 和车机安装授权仍需人工主测，不能把历史 fixture 结果当作当前发布通过。
3. 新安卓机模拟车机已完成主机配对；Debug 测试包通过三星应用沙盒内主动 provision 的 ADB 密钥完成发现与连接，Release 不读取该密钥。该接线只用于本机临时测试，不构成正式车机连接或发布能力。
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

1. 产品口径已确认：03桌面是唯一核心且必装；03 歌词和文件管理器均为可选。Debug 签名 profile 当时使用 `android-real-debug-2026-08-20-v4`，清单按桌面优先排序，歌词的 `required` 字段为 `false`，并由本地 Debug ECDSA 信任根重新签发；该 key 已在 2026-08-22 信任根轮换中废弃，Release 不读取该资料。
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

## 2026-08-21 根文件夹分发契约与安装恢复边界（历史中间实现，已被 V3 单项隔离与 Cloud 字段口径覆盖）

1. 当时的中间实现采用固定三 ZIP 的蓝奏密码根文件夹，并在人工替换 ZIP 后从 APK 动态读取版本、大小和 SHA-256；该版本 / 大小来源已被 V3 签名 `apps[]` 的 `versionCode` / `versionName` / `apkSizeBytes` 取代，ZIP / APK 只保留安装前校验职责。
2. 当时的中间实现曾把未知、缺失、重复或额外文件以及包身份不符升级为整次目录拒绝；该规则已废弃。现行 V3 规则忽略未声明文件，声明 ZIP 缺失 / 重复或某 APP 身份不符只记录当前 APP，只有签名配置、密码或根目录解析本身失败才拒绝整次配置。
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

## 2026-08-22 Cloud Android 配置 V2 迁移收尾（历史中间实现，已被 V3 单项隔离口径覆盖）

1. 客户端已切换为只请求 `GET /api/03helper/android-config` 的 schema V2 envelope；先 Base64 解码并使用原始 payload UTF-8 字节验签，支持 `SHA256withECDSA`（ASN.1 DER）与 `Ed25519`，未知 key / 算法、签名失败、外层或 payload schema 非 2、channel 不符、过期和字段注入均 fail closed。Debug 只信任包内置公钥与固定 keyId，Release 继续保持无公钥时不可用；旧 Debug 本地密码旁路和旧 V1 profile 资产已移除。
2. 当时的中间实现新增源码内置 `InstallerComponentTrustRegistry`，固定 desktop / lyrics / file-manager 的 ZIP、APK entry、包名、Debug 证书和最低 SDK；网络 payload 只能声明固定组件映射与必选性，不能覆盖包身份或兼容参数。该阶段曾要求 desktop ZIP 必须存在，并将实际组件失败升级为整次拒绝；这条门槛已废弃，现行 V3 规则改为声明 ZIP（包括 desktop）缺失或单项失败只记录当前 APP，继续其它 APP，最终由结果页决定是否可进入维护。
3. 已补齐 V2 签名、原始字节、Ed25519、过期、未知 key / 算法、网络身份注入、可选 ZIP 缺失、必选 ZIP 缺失、大小写重复和错误 APK entry 等回归用例；下载日志不再输出异常文本或短时 URL 路径，密码、Cookie、Referer 和短时下载上下文不进入持久状态。
4. 本轮验证通过：`108` 项 `testDebugUnitTest`（0 failures / 0 errors）、Debug / Release Kotlin 编译、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、`check-project-docs.mjs`、`check-skills.mjs` 和 `git diff --check`。Cloud 公共 endpoint 当前只读返回 HTTP `404 Not Found`，真实 staging 配置、根目录枚举、ZIP / APK 动态校验和车机安装因此仍是外部发布阻断，未把 404 当作客户端通过或回退到 V1。
5. 最新 Debug APK 已核对为 `com.tcrrry.helper`、`versionName=0.1.0`、`versionCode=1`、Launcher `MainActivity`，APK Signature Scheme v2 为 true；主包 SHA-256 为 `d2d76c52872ecc03593eb3e0ce5c89d440aee8b2bce5a8dde7e47e60c66f21e7`。最新构建在指定测试手机上完成现有 instrumentation smoke `2/2`，随后再次以保留数据方式覆盖安装主包并启动 `MainActivity` 核对 resumed；未清数据、卸载、降级、重启或触碰车机。
6. 交付用户的最小人工主测：Cloud staging 返回有效 V2 配置后，验证验签与过期门禁、根目录可选 ZIP 缺失、人工替换 ZIP 后 version / size / SHA-256 识别，再验证真实 Android System WebView、车机安装 / 授权 / 可用性和维护态更新流程。任何真实发布资料缺失时停止该条，不把 fixture 或模拟结果写成通过。

## 2026-08-22 Cloud Android 配置 V2 历史字段与空密码对齐

1. 按冻结协议将 `previousVersionsUrl` 与 `previousVersionsPassword` 加入客户端 V2 payload 和运行时配置对象；两字段无默认值，旧 V2 payload 缺字段时继续 fail closed。历史字段只为未来人工选择往期版本准备，当前不进入自动更新、版本比较或备用源。
2. `folderPassword` 与 `previousVersionsPassword` 允许空字符串，空字符串表示无密码；历史 URL 非空时必须为 HTTPS 蓝奏文件夹 URL，且无用户名 / 密码、query 或 fragment；历史 URL 为空时历史密码必须为空。
3. 将蓝奏文件夹 URL 校验收回 `ReleaseSourcePolicy` 唯一 owner，配置解析和根目录适配器共用同一 HTTPS、域名和 `b...` 文件夹路径边界；Debug 配置地址切换为 `https://api-staging.9studio.fun/api/03helper/android-config`，Release 地址保持独立。
4. 已补充历史字段缺失、空密码、历史密码成对关系、历史 URL query / 文件页拒绝以及无密码目录继续向隐藏 WebView 传递空密码的回归用例；当前 `testDebugUnitTest`、Release Kotlin 编译、Debug Lint、项目文档、Skill、本机环境和 `git diff --check` 均已通过。真实 Cloud 200 签名配置、蓝奏目录和设备主测仍待 Cloud candidate 发布后执行。

## 2026-08-22 staging 配置签名信任根轮换

1. 用户明确授权废弃旧 `03helper-real-debug-2026-08-20-v4`，不保留旧 key 兼容；已生成新的 staging P-256 PKCS#8 签名私钥，私钥只保存在仓库外受控目录，不进入 Git、APK、数据库、日志或聊天。
2. 新 `keyId` 为 `03helper-staging-config-2026-08-22-v1`，算法为 `SHA256withECDSA`，客户端内置公钥 SPKI DER SHA-256 为 `8c2573689e87e6c426add9f2249186ec7b6c0e8f44b196ea669c820adb1283d3`；Cloud 必须使用同一私钥签名，另行配置独立的 `ANDROID_CONFIG_ENCRYPTION_KEY_BASE64`。
3. 客户端 Debug 信任资料已切换到新公钥和 keyId；Cloud staging 必须重新构建 / 部署 candidate 后注入新私钥，旧 candidate 或旧签名配置不能作为通过依据。
4. 独立密码学探针确认私钥为 PKCS#8、曲线为 `prime256v1`，派生公钥与 APK asset 一致，实际 `SHA256withECDSA` DER 签名验签通过；配置加密密钥已另行生成并与签名私钥分开保存。
5. 本轮 `testDebugUnitTest` 共 `113` 项通过，Debug / Release 编译、Debug Lint、Debug 主包与 AndroidTest 构建、项目文档 / Skill / 本机环境检查和 `git diff --check` 均通过。最新 Debug APK SHA-256 为 `94d121a574a202d237f626c8e9de9330767b46ec6bdb7c5031c7ee7410f57167`。
6. 指定测试手机上的启动 smoke 为 `2/2` 通过；smoke 结束后再次保留数据覆盖安装最新主 Debug APK，系统返回 `Success`，最终包为 `com.tcrrry.helper`、`0.1.0 (1)`、Launcher `MainActivity`。未安装到车机，未清数据、卸载、降级或重启。
## 2026-08-23 Android 动态 APP 分发客户端改造（历史中间实现，已被 2026-08-24 收口覆盖）

1. 客户端控制面已切换为严格 schema V3：`apps[]`、`environment`、`issuedAtUtc`、`catalogVersion` 和 `catalogRevision`；运行时不再接受 v2 或从 `components` 补回固定清单。客户端按环境 / 频道持久化最高修订号，拒绝签名快照回滚。
2. 当时的中间实现按动态声明处理蓝奏根目录：未声明文件忽略，声明但缺失的可选 APP 单项标记，但 desktop 缺失仍会阻断；该规则已被 2026-08-24 收口覆盖。现行规则对所有 APP（包括 desktop）统一逐项隔离，公共 `Download` 未命中且远端 ZIP 缺失时只生成当前 APP 失败，不冻结整批安装；每个可用 ZIP 仍独立完成单 APK 根目录检查、APK 元数据读取、SHA-256 和本地官方发布者证书根校验。
3. 安装与授权链已支持动态条目和 `sortOrder`；Cloud 的 `deviceSetup` 只能编译为客户端强类型动作，ADB 写入前读取现有安全列表并执行 Android 9 容量预检，写入后回读验证并保留原有条目。
4. 会话、维护持久化和下载界面已增加动态应用行、描述、版本、下载 / 安装状态和单项错误原因；维护刷新可识别新增、移除和不在当前目录的已安装 APP。
5. 收尾验证已完成：`./gradlew test` 的 Debug 单元测试 `125` 项、Release 对应测试套件均为 0 failures / 0 errors；`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleRelease`、`check-local-environment.mjs`、`check-project-docs.mjs`、`check-skills.mjs` 和 `git diff --check` 均通过。Debug 产物为 `app/build/outputs/apk/debug/app-debug.apk`，Release 产物为 `app/build/outputs/apk/release/app-release-unsigned.apk`。
6. 本轮没有可用的在线 Android 设备：本机上下文指定的 ADB 返回空设备列表，因此未执行覆盖安装、instrumentation smoke 或 Android 9 车机授权 / 安装；不把构建和 JVM 测试写成设备闭环通过。Cloud v3 staging 真实配置、蓝奏目录和车机主测继续作为外部待验证项。
7. 本轮不提交、不推送、不发布，也未清数据、卸载、降级或重启设备。

## 2026-08-23 V3 动态目录收尾审计

1. 收尾审计补齐构建变体绑定：Debug 仅接受 `environment=staging`、`channel=debug`，Release 仅接受 `environment=production`、`channel=release`；不匹配的签名快照在目录处理前拒绝。
2. 收紧单项失败边界：ZIP / APK 已通过读取但在缓存写入等环节发生的非取消异常，只生成当前 APP 的 `distribution_app_processing_failed`，并清理该 APP 的暂存缓存，不再把可选 APP 异常升级为整目录失败。
3. 协议文案统一使用 V3 的 `installPolicy` 字段；`required` 仅作为客户端内部的安装建议投影。
4. 授权计划不再为已知 APP 覆盖服务端排序：动态清单传入的 `sortOrder` 直接决定安装 / 授权计划顺序，旧兼容入口保留显式的 0/1/2 默认顺序。
5. 本轮精简验证通过：指定 JDK17 下 Debug / Release 各 `127` 项 JVM 单测均为 0 failures / 0 errors，`./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain` 成功；`check-local-environment.mjs`、`check-project-docs.mjs`、`check-skills.mjs` 和 `git diff --check` 均通过。
6. ADB 当前仍返回空设备列表，因此没有执行覆盖安装、instrumentation smoke 或 Android 9 车机安装 / 授权；未把这些项目写成通过。本轮仍不提交、不推送、不发布，也未清数据、卸载、降级或重启设备。

## 2026-08-23 动态目录最终边界复核

1. 将已知内置 `appId` 的实际包名绑定和 typed `deviceSetup` 包归属校验前移到单 APP 清单构建阶段；错误可选 APP 现在只进入自身失败状态，不会在用户开始批量安装后才拒绝整批。
2. 新增授权组件门禁回归用例；最终 Debug / Release 各 `128` 项 JVM 测试均为 0 failures / 0 errors，Debug Lint、Debug / Release 构建、项目文档、Skill、本机环境和 `git diff --check` 均通过。
3. 最新 Debug APK 为 `com.tcrrry.helper` / `0.1.0 (1)` / `MainActivity`，SHA-256 为 `0993303491d7cda6919e06d11acb9f491eb3e21145f21e3aacf9c93ca54988ff`；Release 未签名 APK SHA-256 为 `3487c596c8ab284d465ac266393645f04f694737e132f5a36a9b2af76e1b46da`。设备仍无在线 ADB，未执行覆盖安装或真实车机动作。

## 2026-08-23 文档口径清理与 Debug 包交付

1. 当前物理边界文案已统一为 Cloud schema V3 动态配置：签名 `apps[]`、`catalogRevision` 防回滚、客户端本地官方发布者证书根校验；来源锚点、产品基线、长期总纲、首版计划、安全边界、验证矩阵和 Cloud 接线均不再把 V2 请求或固定组件映射写成当前主链。
2. 授权边界已统一为按签名 `apps[]` 动态生成的版本化 typed 授权计划；固定综合授权命令只在带日期的历史进度事实中保留，未改写历史记录。
3. 本轮通过 `node scripts/check-project-docs.mjs`、`node scripts/check-skills.mjs`、`node scripts/check-local-environment.mjs`、`git diff --check`、Debug 单测、Debug Lint 和 Debug 构建。最新 Debug APK 为 `com.tcrrry.helper` / `0.1.0 (1)` / `MainActivity`，SHA-256 为 `a254e39d91576c59efd89784a4ec5f260d77f9119fa5583774c99ef705d85687`，APK Signature Scheme v2 验证通过。
4. 已在显式测试手机 `SM-F946B` 上执行保留数据覆盖安装，系统返回 `Success`；随后启动 `MainActivity`，状态为 `ok`，进程保持运行，最近日志未发现应用致命异常。未清数据、卸载、降级、重启或触碰车机包；剩余真实 Cloud / 车机主链交给用户人工主测。

## 2026-08-23 Cloud V3 实际字段对齐与 Debug 交付

1. 只读核对 staging 公共接口确认真实 V3 envelope 在外层携带 `catalogRevision`；payload 追加 `previousVersionsUrl` / `previousVersionsPassword`，每个 APP 携带 `trustProfileId`，`deviceSetup` 使用 `profileId` / `actionIds`。客户端 DTO、版本一致性校验、历史字段传递、本地信任档案和授权计划门禁已同步到该实际协议。
2. `trustProfileId` 只映射到客户端内置 `nine-studio` / `fossify-approved` 信任档案；未知档案、已知 APP 档案错配、未知 profile / action 或包归属不符均 fail closed，不开启未知字段吞掉逻辑。
3. 直接相关的动态配置与根目录适配器 JVM 用例 `19/19` 通过；未执行与本次字段对齐无关的全量 instrumentation 或车机写入测试。
4. `:app:assembleDebug` 成功，Debug APK SHA-256 为 `065f458a975a5f7cfd8450bb2a58d2c32060733e32d53e562b2cf160ff870416`；使用显式测试手机保留数据覆盖安装返回 `Success`，`com.tcrrry.helper` / `0.1.0 (1)` 的 `MainActivity` 已启动并保持前台。
5. 本轮未提交、未推送、未发布，未清数据、卸载、降级或重启设备；真实 staging 根目录下载、车机安装授权和维护动作交由用户人工主测。

## 2026-08-24 Debug 无线车机模拟测试接线（已废弃）

1. 本节记录的 Debug 专用 ADB 密钥旁路仅用于上一轮临时模拟测试，现已从 Debug / Release 组合根、连接工厂和生产运行时全部移除；当前版本不从应用私有目录读取或注入 ADB 密钥。
2. 上一轮普通手机作为模拟车机的运行结果只保留为历史证据，不构成当前设备兼容性或车机安装通过结论；后续以真实车机人工主测为准。

## 2026-08-24 选择页轻量元数据恢复（已被本轮协议字段收口覆盖）

1. 这是本轮收口前的中间实现：根目录 WebView 曾同时兼容蓝奏当前 `#ready` 行结构与旧 `.mbx` 结构，并读取 ZIP 名称、大小和时间；这些目录行字段当时只用于临时轻量展示，现行选择页不再使用它们，直接投影签名 `apps[]` 的 `versionName` / `versionCode` / `apkSizeBytes`。
2. 该中间实现还曾临时使用签名 `catalogVersion` 作为版本标签，现已被 Cloud `versionName` / `versionCode` / `apkSizeBytes` 字段直接取代；`catalogVersion` / `catalogRevision` 只保留配置修订、防回滚和内部追踪职责。
3. 增加展示字段边界和根目录大小回归用例；当前仍不把目录展示值当作 APK 身份证明，安装前完整 ZIP / APK 校验门禁不变。
4. 该中间包的实机观察只保留为历史证据，不构成当前版本字段或设备闭环结论。

## 2026-08-24 Cloud 字段、公共 Download 与单项失败隔离收口

1. Cloud schema V3 的每个启用 `apps[]` 条目现在严格读取 `versionCode`、`versionName`、`apkSizeBytes`；选择页规范化显示 `v1.2.3`、`2.6M`。`catalogVersion` / `catalogRevision` 只用于配置修订、防回滚和内部追踪，不再作为应用版本或包体大小。
2. 用户确认后先扫描安卓系统公共 `Download` 目录（Android Q+ 使用 MediaStore，Android 9 及以下使用公共目录）；APK 复用准入只看包名与受信证书，版本、字节数和 SHA-256 作为证据与更新追踪。未命中时 ZIP / 分片留在应用私有缓存，校验通过的 APK 发布并保留到公共 `Download`；清理只删除助手生成的 `03helper-` APK，不删除用户其它安装包。
3. 远端目录缺少声明 ZIP（包括 `desktop`）不再在选择阶段阻断；本地和远端均不可用时只记录当前 APP 失败，继续其它 APP。安装页只呈现获取、检查、发送、授权、检查可用性五个阶段和当前百分比，取消安装按钮与逐应用进度列表已移除，底部显示保持亮屏提示。全部结束后进入结果页展示逐项失败原因；只有 `desktop` 有完整可用证据时才显示进入维护。
4. 下载 / 目录准备层增加单 APP 未预期异常隔离，异常转为结构化失败并继续批次；安装、授权和可用性阶段沿用逐 APP 失败后继续的会话主链。
5. 本轮验证通过：指定 JDK17 下 `testDebugUnitTest` 共 `138` 项（0 failures / 0 errors）、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:compileReleaseKotlin`、`check-project-docs.mjs`、`check-skills.mjs`、`check-local-environment.mjs` 和 `git diff --check`。
6. 最新 Debug APK 已核对为 `com.tcrrry.helper` / `versionName=0.1.0` / `versionCode=1`，Launcher 为 `MainActivity`，APK Signature Scheme v2 验证通过；主包 SHA-256 为 `17e2da37dd1eb591a4cedd5eaa01c0b55622641060384b8a5bd7fb9d7741207e`。当前上下文指定手机 ADB 返回空设备列表，未执行覆盖安装、instrumentation smoke 或车机动作；不把构建和 JVM 测试写成设备闭环通过。
7. 本轮未提交、未推送、未发布，未清数据、卸载、降级或重启设备；真实 Cloud staging 配置、公共 `Download` 复用、Android System WebView、车机安装 / 授权 / 可用性和维护入口仍交由用户在真实车机上人工主测。

## 2026-08-24 最终 Debug 包交接（覆盖安装受设备连接阻断）

1. 文档口径最终复核已通过：旧的 desktop ZIP 整体阻断、ZIP 行版本 / 大小和 `catalogVersion` 展示口径均明确标记为历史中间实现；现行 V3 规则以签名 `apps[]` 字段展示、公共 `Download` 优先和逐 APP 失败隔离为准。
2. 最新 Debug APK 已核对为 `com.tcrrry.helper` / `versionName=0.1.0` / `versionCode=1` / `MainActivity`，SHA-256 为 `17e2da37dd1eb591a4cedd5eaa01c0b55622641060384b8a5bd7fb9d7741207e`。
3. 收尾第一次尝试使用本机上下文指定的测试手机 mDNS 端点时因 ADB TLS `Connection refused` 未执行安装；无线调试恢复后已用显式 serial `adb-RFCX412AN1X-gWfMRD (2)._adb-tls-connect._tcp` 对该测试手机执行保留数据覆盖安装，系统返回 `Success`，并启动 `com.tcrrry.helper/.MainActivity` 核对进程保持运行。车机 `192.168.0.203:5555` 未连接、未写入。
4. 本轮未提交、未推送、未发布，未清数据、卸载、降级或重启设备；真实 Cloud、公共 `Download` 复用、Android System WebView、车机安装 / 授权 / 可用性和结果页维护入口交由用户人工主测。

## 2026-08-24 测试车机清理

1. 按用户当次明确授权，在样本车机上对 03桌面、03歌词和文件管理器执行 user 0 卸载；三个目标包的安装路径、用户包列表和进程回读均已为空。
2. 卸载前已撤销本次安装链对应的精确授权：桌面悬浮窗 / 安装包 AppOps 与无障碍服务，歌词悬浮窗 / 通知监听 / 无障碍服务，文件管理器存储运行时权限 / AppOps 与安装包 AppOp。
3. 回读确认目标通知监听和无障碍服务已移除，车机原有的 `com.mengbo.monitor` 无障碍服务保留；未触碰其它应用或车机数据。

## 2026-08-24 维护页面版本与操作按钮 UI 收口

1. 更新页现在只呈现一个版本值：最新应用显示 `当前 vX`，可更新应用显示远端目标 `vX` 并使用成功绿强调，未安装 / 不可用状态使用警示色；不再同时显示当前版本和远端版本。断开车机时，车机应用区只显示未连接提示，底部更新按钮和完成提示只依据助手自身状态。
2. 应用管理四个操作统一使用 `IconTextActionButton`；组件固定按钮高度并在 `PressableSurface` 中使用中心对齐，图标与文字几何上水平、垂直居中。未安装应用的启动、强停和卸载按钮使用动画降透明度并保持不可点击，应用详情仍可进入。
3. 指定 JDK17 下 `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 和 `:app:connectedDebugAndroidTest` 均通过；instrumentation smoke 为 `2/2`。
4. 使用显式测试手机完成最新 Debug 主包保留数据覆盖安装，系统返回 `Success`；包身份为 `com.ninepointnine.helper`、`versionName=0.1.0`、`versionCode=1`，启动入口为 `.MainActivity`，APK SHA-256 为 `831744854897124790a3c616b97afe5dd9b42527b9e96a18e1fa8f2f60c940d7`。真实车机安装、授权、启动、强停和卸载按用户要求全部跳过；未清数据、未卸载手机助手、未重启设备。

## 2026-08-24 首次安装选择页、失败恢复与 APK 身份校验修正

1. 首次安装选择页版本统一显示小写 `v` 并保留 Cloud 原始版本内容；“必装 / 可选”与应用名称保持同一行且右对齐，版本号与文件大小独立显示在下一行。客户端不再使用静态产品 Logo；优先从本地已验证 APK / 已安装受信包读取自身图标，缺少本地 APK 时显示中性占位，因此 03投屏图标由其 APK 自身资源决定。
2. 安装失败结果统一显示居中的“安装失败”，移除简介并将操作按钮固定在页面底部；“重新尝试”返回选择应用页，保留当前设备、Cloud 清单和此前可选应用勾选状态，同时清除本次失败尝试状态。
3. 客户端 APK 身份准入收缩为实际包名与发布者证书，Cloud 版本和展示大小不再成为重复身份门禁；传输哈希与实际大小仍作为下载、解压和安装后证据保留。03桌面、03歌词和03投屏的 Debug、staging、production 包身份均按本地受信发布者资料识别，授权计划使用实际包名生成组件入口。
4. 指定 JDK 17 下 `:app:testDebugUnitTest` 共 `142` 项通过（0 failures / 0 errors / 0 skipped），`:app:lintDebug`、`:app:assembleDebug`、项目文档检查、Skill 检查和 `git diff --check` 均通过。
5. 最新 Debug APK 为 `com.ninepointnine.helper` / `versionName=0.1.0` / `versionCode=1` / `MainActivity`，APK Signature Scheme v2 验证通过，Debug 证书 SHA-256 为 `2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27`，APK SHA-256 为 `ec15b7e847e39601f3e0f5ab22a029a262ad81cc6283b736212d66f579312f94`。
6. 已使用本机上下文中的显式 `SM-F946B` 测试手机 serial 保留数据覆盖安装，系统返回 `Success`；安装后包路径、版本、Launcher 均核对通过，`com.ninepointnine.helper/.MainActivity` 已启动并处于前台。为保持用户接手时的最终安装状态，本轮未运行会在收尾移除主包的 instrumentation；未清数据、卸载、降级、重启、提交、推送、发布或对车机执行写入。

## 2026-08-24 安装失败根因与身份 / 图标收口

1. 根因已确认：03桌面与 03投屏的“未安装、未完成授权、不可用”来自旧 Debug 包名 / 组件路径硬编码，03歌词的失败来自旧 Debug 发布证书；当前 staging 包名与证书已进入精确 Debug / staging / release 身份矩阵，授权命令按实际 APK 包名生成。客户端身份准入只依赖包名与发布者证书，Cloud 版本 / 展示大小不再重复作为 APK 身份门禁；ZIP / APK 大小与 SHA-256 仅保留传输完整性证据。
2. 03投屏 APK 本身包含 `ic_launcher.png`；客户端已移除静态 Logo 映射，图标从本地已验证 APK 或已安装受信包读取，并按 APK 内容摘要失效缓存。无本地 APK 时显示中性占位，不伪造旧 Logo。文件管理器的“下载目录中暂时没有这个应用”属于之前上传错误 ZIP 的外部发布问题，当前客户端保留公共 `Download` 优先与单项失败隔离，不增加存储旁路。
3. 失败结果页统一为居中的“安装失败”，移除简介，标题与结果列表保留呼吸间距，按钮固定在页面底部；“重新尝试”清除失败尝试并回到同一连接下的选择页，空清单场景会重新加载目录，不再卡死。
4. 本轮新增图标通道与失败恢复回归覆盖；完整 Debug JVM 单测、Lint、Debug APK 构建及项目文档 / Skill / 本机环境检查结果待本轮收尾命令写入。当前显式手机与车机 serial 是否在线以收尾检查为准。

## 2026-08-24 维护态交互与 Logo 可见性修正

1. 维护二级页返回统一经过会话 owner 清理运行中的维护任务并消费系统返回手势；返回维护首页后不再因遗留 `RUNNING` 状态把全部按钮锁死，已确认连接保持可用。
2. 检查更新与授权页在后台查询期间先显示逐节点检查 / 进度状态；授权页预置受控应用节点并展示 Logo、版本和授权结果，完成文案与所有底部状态提示统一居中。
3. 管理应用的启动 / 强停反馈改为中下部短时暗色浮层；启动成功判定不再把启动后短暂的 `pidof` 延迟误报为失败，仍保留 `am start` 返回错误的失败闭环。
4. 安装应用选择页对已安装应用保持选中并禁用勾选，全部已安装时按钮显示“知道了”；当前受信的桌面、歌词、投屏和文件管理器 Logo 作为本地资源兜底，仅在无已验证 APK 时使用，不改变 APK 身份校验主链。

## 2026-08-24 收尾验证与可测试 Debug 包

1. 增量收口后指定 JDK17 下 `:app:testDebugUnitTest` 为 `145` 项（0 failures / 0 errors），`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`check-project-docs.mjs`、`check-skills.mjs`、`check-local-environment.mjs`、`check-03app-repository.mjs --strict` 和 `git diff --check` 均通过。
2. 最新 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，包名 `com.ninepointnine.helper`，`versionName=0.1.0`，`versionCode=1`，入口 `.MainActivity`；APK SHA-256 为 `a4456a848ec4ceddb25f30e0d10723d63e1d5265b31256f8937a173994fdf2c1`，v2 签名通过，Debug 证书 SHA-256 为 `2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27`。
3. Release Kotlin 编译未执行成功，客观原因是本机未提供仓库外 production signing.properties，Gradle 按安全规则在 `preReleaseBuild` fail closed；没有修改或伪造签名材料。
4. 覆盖安装阻断：显式测试手机 serial 与车机 serial 当前均不在线；mDNS 曾发现手机端点 `192.168.31.220:40953`，连接返回 `Connection refused`，未执行任何设备写入。用户接手时需在手机上线后对上述 APK 执行一次保留数据 `install -r`，车机仍未触碰。

## 2026-08-24 Download 候选身份与单项失败收尾

1. 公共 `Download` 复用现在由组件、包名、当前 environment / channel 对应发布轨道和发布者证书共同准入；旧包只视为本地缓存未命中，保留用户文件并继续解析 / 下载声明的远端 ZIP。03 歌词 staging 证书摘要同步为已验真的 `1eb136fffd3f1e4c204d0933cab66c51ee4536a29e949b9c080925c01563b51d`。
2. 安装、授权和可用性证据按组件聚合；可选组件失败不会抬升为整批失败，仍成功的组件可进入 `COMPLETED_WITH_ERRORS`，结果页保留失败原因。失败结果头部与首个应用格之间增加独立间距，列表拥有独立滚动区域。
3. 直接相关 JVM 回归 76 项通过（0 failures / 0 errors），Debug 构建、APK v2 签名、包名 / 版本 / 启动入口核对通过；最新 APK SHA-256 为 `04a2bb29b3ffd23918c5a664a1f824e266ceccd7a0af0f3fcd263e9f558c6892`。
4. 测试手机恢复无线调试后，已使用显式 serial `adb-RFCX412AN1X-gWfMRD (2)._adb-tls-connect._tcp` 执行保留数据覆盖安装，系统返回 `Success`；包为 `com.ninepointnine.helper`、`0.1.0 (1)`，`.MainActivity` 启动并保持前台。车机 `192.168.0.203:5555` 未连接、未写入；本轮不提交、不推送、不发布。

## 2026-08-24 staging 云端包与 Download 候选实证

1. 只读请求 staging `android-config` 得到 `catalogRevision=4`，03 歌词声明为 `03歌词-staging-20260824.zip`、`versionCode=114`、`versionName=1.14-icar03`、`apkSizeBytes=6536998`；受保护蓝奏目录枚举到同名 ZIP。
2. 通过该目录的短时下载链取得当前远端 ZIP；远端与本机发布候选 ZIP 字节完全一致（`5456348` 字节，SHA-256 `7c0f5978be1235af50a43e61a3a03c13f17e453423057a287384db5d2cfdeb52`）。解压 APK 的包名为 `com.ninepointnine.desktoplyrics`，staging 证书摘要为 `1eb136fffd3f1e4c204d0933cab66c51ee4536a29e949b9c080925c01563b51d`。
3. 测试手机公共 `Download` 同时存在旧 `com.tcrrry.desktoplyrics`、开发证书候选和当前身份候选；旧文件不满足组件 / 包名 / environment-channel 轨道 / 发布者证书联合准入，必须继续远端获取。该证据确认此前“目录存在 APK 即直接复用”是 03 歌词签名不匹配的实际触发条件。
4. 收尾重新通过 `testDebugUnitTest`、`lintDebug`、`assembleDebug` 及项目护栏；最终 Debug APK SHA-256 为 `04a2bb29b3ffd23918c5a664a1f824e266ceccd7a0af0f3fcd263e9f558c6892`。使用显式无线调试 serial 保留数据覆盖安装返回 `Success`，`MainActivity` 启动 `Status: ok`，前台无致命异常；车机未连接、未写入。

## 2026-08-24 最终交付状态核对

1. 当前 `app/build/outputs/apk/debug/app-debug.apk` SHA-256 为 `3245d19eb88de26aebe35e74f20563595f9ea9c28285906280e6ea648cc1a372`；与显式测试手机 `adb-RFCX412AN1X-gWfMRD (2)._adb-tls-connect._tcp` 上已安装包逐字节一致，包名 `com.ninepointnine.helper`、版本 `0.1.0 (1)`，正式入口 `.MainActivity` 已置于前台。
2. 本轮收尾护栏 `check-03app-repository.mjs`、`check-project-docs.mjs`、`check-skills.mjs` 和 `git diff --check` 均通过；此前本轮代码验证的 JVM 单测、Lint、Debug 构建与设备 smoke 结果继续有效。
3. 车机 `192.168.0.203:5555` 当前仍未在线，因此真实车机安装、授权、启动 / 强停和返回重连链路未宣称通过；未对车机执行写入、清理、卸载或重启。

## 2026-08-25 Logo、维护实时库存与检查更新收口

1. 本轮完成 Logo 与车机实时状态收口：已验证 APK / 持久化 APK Logo 优先于内置产品 Logo；运行时不再请求远程 Logo，维护页发起实时库存读取期间也不继续展示旧远程预览。维护快照仍保留签名 Logo 元数据以兼容配置协议。
2. 检查更新改为只刷新签名控制面配置，并以 `catalogVersion`、`catalogRevision`、展示元数据和车机实时 `versionCode` 交叉比较；不再打开蓝奏 WebView、枚举目录、下载 ZIP 或解压 APK。ADB 优先读取带版本号的包清单，旧系统回退普通包清单；检查开始和失败时清除旧结果。
3. 授权页、管理已安装应用页和安装应用页均由同一受控组件集合及车机 ADB 实时包库存驱动，文件管理器没有页面特判。卸载动作以 `pm path` 回读确认包已消失，成功后同步移除会话库存；重新进入管理 / 安装流程会重新扫描车机，避免把旧缓存投影为已安装。
4. 指定 JDK17 下 `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 和 `:app:connectedDebugAndroidTest` 均通过；JVM 单测共 `160` 项（0 failures / 0 errors / 0 skipped），instrumentation smoke 为 `2/2`。
5. 最新 Debug APK 已核对为 `com.ninepointnine.helper` / `versionName=0.1.0` / `versionCode=1` / `MainActivity`，v2 签名验证通过；已在显式测试手机上执行保留数据覆盖安装并启动，系统返回 `Success`，进程保持运行且未发现应用致命异常。车机当前未在线，真实车机授权、管理、安装、卸载和检查更新闭环仍待车机人工主测。
6. `check-project-docs.mjs`、`check-skills.mjs`、`check-local-environment.mjs` 和 `git diff --check` 通过；`check-03app-repository.mjs --strict` 仅因登记快照 HEAD 与实际 HEAD 不一致而失败，未修改登记库或提交来掩盖该差异。本轮未提交、未推送、未发布。

## 2026-08-25 维护空态、卸载回读与安装清单最终收口

1. 维护库存改为显式 `NOT_STARTED / LOADING / READY / FAILED` 状态；管理已安装应用页和修复授权页在读取完成但车机没有受控应用时显示 `当前无已安装应用`，修复授权页不再显示 `重新授权`。安装应用页的空配置显示 `当前没有可安装应用`，用 `知道了` 结束，不留下无效的禁用安装按钮；所有空态文案统一居中并保留底部呼吸空间。
2. 卸载链路接受带无害框架诊断的成功回执，并以 `pm path` 的有界回读作为成功后置条件；已不存在的目标按幂等成功处理。卸载完成后立即重新读取车机库存并更新当前会话，返回维护首页或再次进入管理页都不依赖旧缓存。
3. 安装应用页以完整签名配置清单为基线，再与车机实时库存交叉分成“已安装 / 未安装”；部分安装、卸载或重连只更新对应结果，不会用本次选中的 APK 子集替换完整应用列表。完整配置、库存状态、失败原因和可重试信息均进入维护持久化，并兼容旧存档。
4. 本轮验证通过：`:app:testDebugUnitTest` 共 `167` 项（0 failures / 0 errors / 0 skipped）、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`node scripts/check-project-docs.mjs`、`node scripts/check-skills.mjs`、`node scripts/check-local-environment.mjs` 和 `git diff --check`。无线调试恢复后，最新代码在显式测试手机上的 instrumentation smoke 为 `2/2`；随后已再次覆盖安装最终主包并启动核对。
5. 最新 Debug APK 已构建并核对为 `com.ninepointnine.helper`、`versionName=0.1.0`、`versionCode=1`、入口 `.MainActivity`，SHA-256 为 `a78d4f6317c8b3896e98942983cb131660980d51718c4587de21977189171303`；测试手机安装返回 `Success`，`MainActivity` 处于 resumed。车机 `192.168.0.203:5555` 仍未在线，真实车机授权、卸载、检查更新和安装页交叉主测仍待目标车机人工执行。本轮未提交、未推送、未发布，也未对车机写入。

## 2026-08-25 施工收尾复核（设备离线）

1. 使用指定 JDK17 强制重跑 `:app:testDebugUnitTest --rerun-tasks`，167 项测试全部通过；随后强制重跑 `:app:lintDebug`、`:app:assembleDebug` 和 `:app:assembleDebugAndroidTest`，均构建成功。
2. 最新 Debug APK 重新核对为 `com.ninepointnine.helper`、`versionName=0.1.0`、`versionCode=1`、入口 `.MainActivity`，SHA-256 为 `5bf1e2544ef5e68b548337f76d0eafd07b8856161e5751ffc296dbd6d8423d1e`；APK Signature Scheme v2 验证通过。未执行覆盖安装，因为显式测试手机与车机的 ADB 设备列表均为空。
3. `check-project-docs.mjs`、`check-skills.mjs`、`check-local-environment.mjs` 与 `git diff --check` 通过。`check-03app-repository.mjs --strict` 仅报告登记快照 HEAD 与当前工作树 HEAD 不一致；未修改登记库、未提交、未推送、未发布。
4. 本轮未把设备 smoke 写成通过；真实车机的维护空态、授权、实时库存、卸载回读和完整配置与车机库存交叉仍是用户接手后的最小人工主测范围。
5. 同步修正 `docs/testing/验证矩阵.md` 中过时的“卸载不可用”表述，明确受控卸载、空库存和完整配置与车机库存交叉的现行验收口径。

## 2026-08-25 施工后验证与 Logo 口径同步

1. 修正并发 Logo 失败夹具的线程安全问题后，指定 JDK17 下 `:app:testDebugUnitTest --rerun-tasks` 共 `168` 项全部通过；`:app:lintDebug`、`:app:assembleDebug` 和 `:app:assembleDebugAndroidTest` 均通过。
2. 最新 Debug APK 已核对为 `com.ninepointnine.helper`、`versionName=0.1.0`、`versionCode=1`、入口 `.MainActivity`；APK Signature Scheme v2 验证通过，主包 SHA-256 为 `bd3bf073e51689ff52a48bc0c7e0dab3f34298eb72c0cf0935fd201cc7118169`，AndroidTest 包 SHA-256 为 `e7d46eff4e1930d9e7a297398e50b63c5615eec303af2916deaae2ae34fc0f5d`。
3. 同步修正协议、产品、安全、架构、计划和验证文档：v4 `icon` 仅保留配置兼容 / 发布校验，运行时不请求远程 Logo；初始化使用内置资源，匹配 APK 验签后按版本与 APK 摘要缓存 APK 内图标。
4. 项目文档、Skill、本机环境检查和 `git diff --check` 均通过。`check-03app-repository.mjs --strict` 仍仅因登记快照 HEAD 与当前工作树 HEAD 不一致而失败，未修改登记库掩盖差异。
5. 当前 `adb devices -l` 无在线设备，未执行覆盖安装或 instrumentation smoke；真实车机的维护空态、实时库存、卸载回读、授权和安装清单交叉仍是最小人工主测范围。本轮未提交、未推送、未发布。
6. 追加收口两项边界：安装前从本地 APK 读取的 Logo 立即进入持久缓存；卸载命令只要 `exitCode=0` 且无失败标记就进入包消失后置条件回读，兼容无字面 `Success` 的 Android 9 回执。相关单测与 Debug 构建已重新通过。

## 2026-08-25 无线调试恢复后的最终覆盖安装

1. 使用显式测试手机 serial `adb-RFCX412AN1X-gWfMRD (2)._adb-tls-connect._tcp` 对最新 Debug APK 执行保留数据覆盖安装，系统返回 `Success`；未清数据、未卸载、未降级、未重启设备。
2. 安装后核对包身份为 `com.ninepointnine.helper`、`versionName=0.1.0`、`versionCode=1`、入口 `.MainActivity`，v2 签名仍有效；随后启动返回 `Status: ok`，当前焦点为 `MainActivity`，界面已进入维护页并显示四个维护入口，最近日志未发现 `FATAL EXCEPTION`。
3. `check-project-docs.mjs`、`check-skills.mjs`、`check-local-environment.mjs` 与 `git diff --check` 通过；`check-03app-repository.mjs --strict` 仅因登记快照 HEAD 与当前工作树 HEAD 不一致而失败，未修改登记库掩盖差异。
4. 车机 `192.168.0.203:5555` 仍未在线；真实车机维护空态、实时库存、卸载回读、授权和完整配置交叉仍交由用户手测。本轮不提交、不推送、不发布。
## 2026-08-25 维护安装失败恢复边界修正

1. 根因确认：维护安装启动后原选择被清空，失败结果仍派发初始化安装的 `ReturnToSelection`，导致领域状态回到 `CONNECTED`，UI 投影成首次安装选择页。
2. `InstallationSessionSnapshot` / `SessionCheckpoint` 新增 `installationFlow`，维护安装保留选择快照并使用独立恢复命令；维护失败返回维护“安装应用”选择页，初始化失败路径不变。
3. 维护安装开始时只保留可复用已安装应用的既有证据，避免结果页把已安装 03桌面显示为“未安装”。新增领域回归覆盖恢复路由、原勾选和已安装证据。

## 2026-08-25 维护安装失败恢复收尾

1. 使用 JDK17 强制重跑 `:app:testDebugUnitTest --rerun-tasks`，全量 `181` 项 JVM 单测通过（0 failures / 0 errors / 0 skipped）；`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、项目文档、Skills、本机环境和 `git diff --check` 均通过。
2. 显式测试手机上的 `InstallAppActivitySmokeTest` 2/2 通过；随后再次保留数据覆盖安装最新 Debug 主包并启动核对。最终包为 `com.ninepointnine.helper`、`0.1.0 (1)`、入口 `.MainActivity`，主包 SHA-256 为 `3be3b00cf833f7ecafbed5458723d825b75c178648cb039083782ab7b0007659`。
3. 本轮未清数据、未卸载、未降级、未重启设备，未对目标车机写入；不提交、不推送、不发布。

## 2026-08-26 施工方案审查与边界收口

1. 复核当前实现后保留既有主架构：`InstallationSession` 仍是唯一状态 owner，`InstallationBatchPlan` 冻结一次批次，`InstallerRuntime` 只编排适配器，设备协调器只消费已验证产物；没有引入第二套状态机或推倒重写。
2. 修正准备结果边界：`InstallerRuntime` 现在会把 typed component failure 归一为会话事件，并要求新鲜组件集合与已成功准备集合一致；缺失且未标记失败的组件以 `artifact_preparation_incomplete` fail closed，设备执行回调不会被调用。新增回归覆盖该契约。
3. 修正维护快照边界：`MaintenanceSessionStore` 继续严格解析旧路由字段，但不在冷启动恢复没有完整页面 wire model 的二级路由，始终回到维护首页；新增存档回归覆盖安装应用路由。
4. 指定 JDK17 顺序化执行 `:app:clean :app:testDebugUnitTest`，全量 `202` 项 JVM 单测通过（0 failures / 0 errors / 0 skipped）；随后 `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:app:lintDebug`、`:app:assembleDebug` 和 `:app:assembleDebugAndroidTest` 全部通过。项目文档、Skills、本机环境和 `git diff --check` 通过。
5. 最新 Debug 主包已核对为 `com.ninepointnine.helper`、`versionName=0.1.0`、`versionCode=1`、入口 `.MainActivity`，v2 签名验证通过；主包 SHA-256 为 `d4406f624f03118f46a25afe46c9f23f4b919c66fe4353395a6e44958d69e770`，AndroidTest 包 SHA-256 为 `e7d46eff4e1930d9e7a297398e50b63c5615eec303af2916deaae2ae34fc0f5d`。显式测试手机保留数据覆盖安装两次均返回 `Success`，instrumentation smoke `2/2` 通过，最终主包启动返回 `Status: ok`。
6. `check-03app-repository.mjs --strict` 仅因当前未提交工作树 HEAD 与登记快照不一致而 fail closed；未修改登记库。目标车机仍不可用，真实车机安装、授权、库存和维护写入继续保留为人工主测阻断；本轮未清数据、未卸载、未降级、未重启设备，不提交、不推送、不发布。

## 2026-08-27 Android 9 真实车机授权根因修复与闭环

1. 真实故障不是 ADB 连接失败，而是四个边界叠加：Android 9 `mksh` 会把原动态授权解析中的 `${spec%%|*}` 误按模式分支处理，导致动作类型为空并返回 `authorization_action_invalid`；安装身份回读成功后没有在同一领域事件内固化受信 manifest，临时批次清单清空后旧会话无法重建授权计划；授权阶段失败错误覆盖了已经成功的实时库存状态，页面因此误报“暂时无法读取车机应用”；03投屏的合法零动作授权计划又被额外的非空 evidence 门禁误判为失败。
2. 修复保持 `InstallationSession` 为唯一状态 owner：动态授权动作改为 Android 9 兼容的固定 `IFS='|'` 字段解析并严格校验字段数、非空值和动作白名单；安装身份验证通过时原子合并 `installedManifests`；维护库存与授权流分别持有状态，授权失败不再污染已为 `READY` 的库存；授权成功统一投影领域完成态；成功门禁只委托版本化授权计划校验证据，因此零动作计划允许空 evidence。旧会话仅在实时回读证明全部已授权时走无写入的幂等完成路径；若仍有未授权项且缺少受信 manifest，继续 fail closed，不猜包名或签名身份。
3. 自动验证已通过：安装身份固化与冷启动、库存和授权状态分离、初始库存失败、授权完成、零动作授权、Android 9 shell 兼容及旧会话幂等恢复等四个聚焦测试类共 `94` 项通过；随后 `testDebugUnitTest`、Debug Kotlin / AndroidTest 编译、Debug Lint、主包与 AndroidTest 构建全部通过，项目文档、Skills 和 `git diff --check` 护栏通过。
4. 在 `S56_HQX`、Android 9 真实车机上进入“修复授权”后，首轮实时库存同时显示 03投屏 `0.1.0`、03桌面 `0.1.0`、03歌词 `1.14-icar03`，三项均为“授权正常”，页面显示“检查完成，所有应用授权正常”，没有再出现“暂时无法读取车机应用”。点击“重新授权”后页面显示“授权完成，所有应用授权正常”，库存保持完整。
5. 真实授权回读前后，`accessibility_enabled=1`，无障碍列表完整保留原第三方 `com.mengbo.monitor/.service.KeyEventService`、03桌面和03歌词服务，通知监听仍为03歌词；桌面与歌词相关 AppOps 模式保持 `allow`，03投屏保持合法的零动作状态。AppOps 输出中仅“距上次使用时间”的相对文本自然变化，没有授权值变化；该旧会话因此完成幂等恢复，未覆盖第三方条目。
6. 最终 Debug APK 为 `com.ninepointnine.helper`、`versionName=0.1.0`、`versionCode=1`、入口 `.MainActivity`，SHA-256 为 `00c04ecc29d9f9fbbec1e2c5dc3b7fbc6cc1d0cacc20cbd1c6768f8d9d0de72a`；单一 Debug signer，证书 SHA-256 为 `2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27`，APK Signature Scheme v2 验证通过。真实 smoke 结束后已对显式测试手机执行最后一次保留数据覆盖安装，系统返回 `Success`；冷启动返回 `Status: ok`，正式入口处于 resumed，旧维护会话仍恢复为已连接 `S56_HQX` 的维护首页。
7. `check-03app-repository.mjs` 唯一阻断仍是共享登记中的仓库 HEAD 快照 `53898143ab42a08ded0c68431e523312a4ec61fd` 落后于当前 HEAD `d6ee6a0b05c616e5384977fb986c58b42893b21f`；这不是包名、版本、签名或产物身份冲突，未擅自改写 Cloud 登记。本轮未清数据、卸载、降级、重启、安装 Release、提交、推送或发布。

## 2026-08-27 单一会话架构文档固化与人工主测交接

1. 将本轮根因收口为长期边界：`InstallationBatchPlan.batchId` 只表示业务安装尝试，`InstallationSessionSnapshot.sessionId` 只表示适配器事件代次；`FolderEntrySnapshotStabilizer` 只在目标条目齐全或既有有界截止点固化目录快照；结果页只读取当前批次 `resultComponentIds`；维护基线保存独立使用 `NOT_ATTEMPTED / SAVING / SAVED / FAILED`；Android 9 Service 回读统一由 `BoundServiceEvidenceParser` 兼容完整名和 `package/.ShortClassName` 缩写。
2. 上一轮已完成的代码、全量工程验证、手机 instrumentation smoke 和目标车机只读实证作为本次交接基线复用，本次没有重复运行这些项目。最终 Debug APK 为 `com.ninepointnine.helper`、`0.1.0 (1)`、入口 `.MainActivity`，SHA-256 为 `fcd7af50ed2dc5be44941198fef965b69912711f6713b75611bdee14bb734e93`；Debug 证书摘要为 `2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27`，APK Signature Scheme v2 已通过。
3. 最终手机交付使用显式 serial `adb-RFCX412AN1X-gWfMRD._adb-tls-connect._tcp` 保留数据覆盖安装，安装返回 `Success`，包身份、版本和启动入口核对一致；`.codex/local-context.properties` 已同步为该 serial。历史进度中的旧 serial 文本保留作审计，不再作为当前设备配置。
4. 当前样本车机 `S56_HQX`（Android 9，`192.168.0.203:5555`）只读回读已确认 03桌面、03歌词和 03投屏库存，授权状态为正常；Android 9 缩写 Service 证据为 `requested=true`、`received=true`、`hasBound=true`。本轮没有对车机执行安装、授权写入、清数据、卸载、降级或重启。
5. 文档长期总纲、验证矩阵和代码规则已同步上述边界；`check-project-docs.mjs`、`check-skills.mjs` 和 `git diff --check` 通过。当前交给用户的最小手测是完整“安装应用”主链及连续批次结果、授权结果和维护基线保存警告，不能把车机只读证据扩写为车机写入已通过。本轮不提交、不推送、不发布。

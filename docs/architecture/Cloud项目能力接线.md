# Cloud 项目能力接线

## 1. 文档定位

1. `cloud` 是 9.9 Studio 的通用云服务 monorepo，承载官网、Cloudflare Worker / Pages、release index、R2、GitHub Releases、API、后台和通用 SDK；它不是 03helper 的源码模块。
2. 本文把 Cloud 当前已经存在的能力、可以复用的契约和仍需为 Android 新建的适配分开记录，供后续 03helper 对话先读。
3. 本文只记录公开入口、仓库路径和接线边界，不记录 Cloudflare token、R2 密钥、GitHub token、生产配置或部署凭据；也不把“文档已有方案”写成“已经部署”。

## 2. Cloud 能力地图

| 能力 | Cloud 物理主链 | 当前可确认事实 | 03helper 的使用方式 |
|---|---|---|---|
| 03 产品官网与安装教程 | `cloud/apps/website-next/`；`/icar03/03lyrics`、`/icar03/03desktop`、`/icar03/tutorial` | 新官网有统一的 iCAR 03 产品页、产品画面、安装教程、下载收口和主题切换 | 只复用信息层级、平面分区、细边框、克制留白和图标语言；不把官网页面或车型视觉嵌入安装助手 |
| Cloud 发布索引 | `cloud/docs/protocols/update-contract.md`、`cloud/docs/release/` | 当前通用更新契约和自动发布流程以 TileLauncher 桌面包为主要实例，包含版本身份、源选择、缓存、hash、大小和回滚指针 | 复用“单一 release index、组件身份、来源切换、摘要和回滚”原则；Android 组件需要独立 profile / schema 适配，不能直接套用 `win-x64` |
| Cloudflare R2 | Cloud release 流程与公开下载域名约定 | Cloud 已有 R2 发布目录和公开下载域名能力；具体线上候选和 production 状态以 Cloud 固定 SHA、staging attestation 和部署文档为准 | 作为蓝奏云失败时的第一自动备用 ZIP 对象源；只承载同一 ZIP 字节和小清单，不把 R2 凭据放进客户端 |
| GitHub Releases | Cloud release 流程的公开镜像链 | Cloud 将 GitHub Releases 作为公开下载镜像；TileLauncher 当前不把它当机器自动更新主源 | 03helper 可以在自己的 Android 发布契约中定义第二自动备用 ZIP 源；在适配器施工前不能声称现成可用 |
| Cloud API | `https://api.9.9studio.fun/`、staging 对应 API 入口及 `cloud/apps/*` | Cloud 按 Telemetry、Update、Identity、Entitlement、Payment、Admin、Website 等能力分域，不提供万能接口 | V1 只考虑获取小体积组件清单或公告；不接账号、支付、权益或车辆数据，不把 ADB 过程上传云端 |
| 产品目录与商业信息 | `cloud/products/03lyrics/`、`cloud/products/03desktop/` | Cloud 保存产品接线、商品和商业配置；产品 APK 业务实现与签名仍在各自仓库 | 只读取已经确认的产品身份和发布元数据；不能把商品目录当作 APK 下载清单或安装命令来源 |
| 通用 Telemetry | `cloud/packages/dotnet/NineStudio.Telemetry/`、对应 API | 通用 SDK 处理队列、落盘、匿名设备 ID、上传、重试和退出 flush | V1 不默认接入；若未来需要，只发送安装阶段、版本和脱敏错误类别，不发送车机地址、完整 shell、个人文件或 APK 内容 |

## 3. 03helper 目标接线

### 3.1 控制面与对象流分离

1. 03helper 需要一个很小的 schema V4 签名配置作为控制面，配置描述当前蓝奏根文件夹、运行时密码、`environment`、`channel`、`catalogVersion`、`catalogRevision` 和动态 `apps[]`；每个条目可声明 `versionCode`、`versionName`、`apkSizeBytes`、展示信息、安装策略、排序、客户端能力门槛、受限 typed `deviceSetup` 和带 SHA-256 的 `icon` 元数据。包名、证书、最低 SDK、APK 哈希和实际身份由客户端读取并用官方发布者证书根校验；Cloud 版本 / 大小只负责展示与发布追踪，不再作为重复 APK 身份门禁。v3 仅作为无 Logo 历史配置的过渡读取。
2. 安装包对象流不经过 03helper 自有服务器。客户端每次从配置取得同一个受密码保护的蓝奏根文件夹，在隐藏 WebView 中完成验证并枚举文件，只定位启用 `apps[]` 声明的 ZIP；未声明文件忽略，缺失 ZIP（包括 `desktop`）不在目录阶段把整次安装判死。用户确认后先扫描手机公共 `Download`，只有没有通过包名 / 受信证书身份校验的本地 APK 时才下载对应 ZIP；单个远端或本地来源失败逐项隔离，结果页再决定是否允许进入维护。最新版本展示以签名 `apps[]` 的 `versionCode`、`versionName`、`apkSizeBytes` 为准，`catalogVersion` 只作配置修订。
3. R2 / GitHub Releases 不参与当前根文件夹自动版本判断；后续若增加备用对象，必须继续由同一签名配置声明并保持组件版本一致，不能让多个来源各自决定“最新版本”。
4. 600GB/月额度只用于评估对象流量，不用于否定小清单控制面；几 KB 的清单请求不构成 APK 直链流量。

### 3.2 Android 清单适配边界

V4 已冻结独立的 Android distribution-config 控制面；客户端仍兼容读取没有 Logo 的 v3 历史快照。领域对象和签名 envelope 的本地实现位于 `com.ninepointnine.helper.domain.artifact` 与 `data/catalog`；这只代表客户端协议已实现，不代表 Cloud 生产配置已经发布。

公共生产入口为 `GET https://api.9.9studio.fun/api/03helper/android-config`，Debug staging 入口为 `GET https://api-staging.9.9studio.fun/api/03helper/android-config`。新 envelope 使用 `schemaVersion=4`，客户端同时兼容 `schemaVersion=3` 的无 Logo 历史快照；payload 包含 `environment`、`channel`、`issuedAtUtc`、`expiresAt`、`catalogVersion`、`catalogRevision`、文件夹字段、动态 `apps[]` 和 v4 `icon` 元数据。客户端先对 Base64 解码后的原始 UTF-8 字节验签，再严格解析 payload；payload 最大 512 KiB，未知字段、危险文件名、过期快照和修订回滚均拒绝。

当前 staging 信任根已轮换为 `keyId=03helper-staging-config-2026-08-22-v1`，算法为 `SHA256withECDSA`、P-256 (`prime256v1`)，客户端内置公钥 SPKI DER SHA-256 为 `8c2573689e87e6c426add9f2249186ec7b6c0e8f44b196ea669c820adb1283d3`。Cloud 只能注入与该指纹匹配的 PKCS#8 私钥；旧 `03helper-real-debug-2026-08-20-v4` 已废弃且不做兼容。配置加密密钥 `ANDROID_CONFIG_ENCRYPTION_KEY_BASE64` 与签名私钥是两套独立材料。

历史版本字段不属于当前 v4 主链；回滚通过新 `catalogRevision` 指向旧的不可变目录完成。客户端按 `environment + channel` 持久化最高修订号，旧签名快照不能覆盖新状态。

#### 03helper 自身发布身份交接

03helper 本体不是 03 歌词或 03桌面的组件 APK，Cloud 建档和许可证下发必须使用独立产品身份：

```text
productId = 03helper
displayName = 03车机助手
androidPackage = com.ninepointnine.helper
runtimeIdentifier = icar03
releaseVersion = 1.0.0 (versionCode 1)
```

| 环境 | 证书 SHA-256 | 构建方式 |
|---|---|---|
| staging | `aca4f178fea11ccc97a1373c8aa5345b274a3a783398929a9340a79ee83663af` | `assembleDebug` + `helperSigningEnvironment=staging` |
| production | `31ca80dd21a5208eaabd5f3e1440a3db2f7dc79122e03eaa6ba01730fb31f18b` | `assembleRelease` + `helperProductionSigningPropertiesFile` |

交接请求是：Cloud 先登记 `productId`、包名、环境和公开证书摘要，再评估并按现有受控流程建立 / 下发对应许可证或 profile；这项外部发布尚未在本仓库宣称完成。Cloud 不接收 JKS、口令、私钥或本机路径。若许可证要被客户端强制校验，必须另行冻结 Android 许可证协议和客户端门禁；当前 03helper V1 只消费签名 Android distribution-config，不把账号权益或许可证结果写入安装状态机。

包名、证书 SHA-256 和最低 SDK 不由网络 payload 覆盖：包名、版本和最低 SDK 从 APK 读取，证书必须属于客户端内置官方发布者证书集合及其包名命名空间。未知算法、未知 key、字段缺失、过期或桌面条目缺失均 fail closed。

客户端从实际归档构造的 `ArtifactManifest` 仍包含 `archiveFormat`、`archiveSizeBytes`、`archiveSha256`、`apkEntryName`、`apkSizeBytes`、`apkSha256`、`packageName`、`apkVersion` 和 `certificateSha256`；这些字段不是 Cloud v3 payload 字段，而是本地校验结果。

`apps[]` 的数量和条目由 Cloud 动态维护；`desktop` 必须启用，其余 APP 可选，首次进入 03helper 选择页时默认全部选中，用户可取消。配置不得携带任意 shell、脚本、第三方直链转换服务或任意包体 URL；`deviceSetup` 只能使用客户端预定义强类型动作。Cloud 只提供签名配置和受控文件夹发布能力；发现设备、ADB、安装、授权、解压和运行验证仍由 03helper 自己负责。

### 3.3 Android ZIP 发布流程

1. 各产品仓库先生成已签名 APK，分别由 03 歌词、03桌面和文件管理器仓库负责包身份、版本和证书；03helper 不复制产品源码，也不重新签名。
2. Cloud release 流程为每个 APP 生成一个 ZIP，归档根目录只放一个 APK；服务端只保存 `apps[]` 中的文件名和展示 / 安装策略。
3. 全部 ZIP 上传到同一个受密码保护的不可变蓝奏目录。目录可包含人工维护的其它文件；客户端只处理签名 `apps[]` 声明的 ZIP，任一声明 ZIP 都允许在目录阶段暂时缺失，因为公共 `Download` 可能已有可复用 APK。
4. 客户端连接后先重新打开根文件夹并按动态文件映射发布轻量应用列表；用户确认后先检查公共 `Download`，再对未命中的已选 APP（最多 2 个并发）下载、解压、读取 APK 元数据并生成本次 `ArtifactManifest`；任一 APP 失败只记录原因并继续其它 APP，`desktop` 失败也必须落到统一结果页。
5. 只有正式 staging 下载、解压、APK 包身份 / 证书校验和 ADB 安装验证全部通过，才允许把签名配置切到公开状态。当前真实组件 Debug 包只进入 03helper Debug 变体的本地签名验证 profile；它们可以证明客户端完整工程链，但不代表 Cloud production 配置或公开候选通过。
6. 密码只作为签名 payload 的运行时字段下发给客户端；Cloud 不向客户端下发网盘账号、R2 密钥、GitHub PAT 或任意命令，客户端不记录密码和短时下载上下文。

### 3.4 当前 F3 真实 Debug 验证资料

1. Debug 配置的根文件夹地址和 `apps[]` 由服务端签名 payload 提供；客户端按动态 `archiveFileName` 映射处理实际声明的 ZIP。
2. 每个声明 ZIP 根目录只能有一个 APK；APK 包名、版本、最低 SDK 和证书由客户端从归档读取，证书与包名必须落在本地发布者信任根内。Debug 使用完整安装、按签名 `apps[]` 生成的版本化授权计划和可用性主链；03桌面是唯一必装核心，其他组件可选。
3. 蓝奏页面验证后产生的 `zipN.webgetstore.com` 地址带短时上下文，只在内存中交给原生下载器，不能写入配置、文档或诊断。
4. 这些资料只用于 Debug 工程验证，不代表 Cloud production 配置、正式签名或公开候选已经发布；根文件夹密码不写入仓库文档。

## 4. 官网视觉复用边界

Cloud 最新 iCAR 03 官网已确认的可复用语言：

1. 当前真值来自 Cloud 的 `apps/website-next/src/components/icar03-site/`、`apps/website-next/docs/research/components/icar03-product-pages.spec.md` 和 `apps/website-next/docs/research/sticai.com/VISUAL_QA.md`；03helper 对话需要继续核对官网时从这些入口读取，不回到已封存旧站。
2. 官网使用 `768px` 平面内容轨道、白色内容面、浅灰页面底、`1px` 细分隔线、无外层阴影、克制留白和 Lucide 线性图标；内容区常用 `24px` / `40px` 横向内边距，按钮使用中小圆角而非大圆角营销卡片。
3. 03歌词与 03桌面能力区以整段网格和边界组织内容，不把每一项做成漂浮卡片；标题、说明、行动按钮和状态信息层级清楚，交互入口不依赖装饰图形。
4. 安装助手把上述结构语言转译成手机任务界面，但采用用户确认的独立配色：首次安装与维护态均为整屏浅蓝背景，标题、说明、步骤、状态、图标和进度以白色为主；只用低透明度白面和细白边界组织内容，不使用浅灰页面或大面积白底工作区。
5. 安装成功后的维护首页仍保留蓝色背景，使用大号、极简、按用途分组的分类按钮；不退回小型白底列表、漂浮卡片或通用后台面板。
6. 官网的车型头图、`48px` 产品标题、产品轮播、赞助区、营销 CTA、页脚蓝雾和主题切换属于网站内容能力，不进入安装助手首屏。安装助手也不使用卡片嵌套、装饰渐变、车型图或官网导航。
7. 官网采用约 `150ms` 的基础过渡；安装助手沿用非线性、短促、克制的动效原则，并扩展为强制覆盖页面进入 / 退出、按钮出现 / 消失 / 按压、加载、进度与结果切换。具体时长与缓动只以 `docs/plans/V1应用功能与UI施工文案.md` 为准。

## 5. 绝对边界

1. Cloud 不执行手机或车机 ADB，不保存设备地址，不接收任意 shell，也不替安装助手决定授权命令。
2. 03helper 不直接读取 Cloud 的服务端密钥，不把 R2 Access Key、GitHub PAT、Cloudflare token 或发布私钥打进 APK。
3. Cloud 现有 TileLauncher 更新协议不能被描述成 Android 组件发布已经完成；必须先完成 Android distribution-config 接口、签名字段、根文件夹发布脚本和 staging 验证。
4. 官网当前下载链接、Cloud 当前线上版本和 R2 当前对象状态都不能仅凭源码宣称已上线；必须以 Cloud 的 release / deployment 文档和固定候选证据为准。
5. V1 不接账号、支付、权益、Telemetry 或车辆控制；这些能力即使在 Cloud 已存在，也必须由新的产品需求和协议单独授权。

## 6. 后续施工顺序

1. M0：Cloud 侧完成 Android distribution-config API、公钥轮换、三个产品仓库的包名 / 签名身份确认、根文件夹文件名和发布责任；03helper 本地 schema 已完成。
2. M1：03helper 本地密码根文件夹 WebView、动态 ZIP 下载器、完整性校验、设备动作和 `InstallationSession` 事件接线已完成；当前 Debug 根文件夹只用于 F3 完整工程验证，不升级为 Cloud production 候选。
3. M2：待 Cloud 提供 staging 配置和根文件夹替换流程后，执行密码验证、目录集合、动态版本识别、ZIP / APK 双重摘要、缓存清理和回滚指针人工验证；若未来增加 R2 / GitHub 备用，再另行验证切源。
4. M3：如需由 Cloud 官网提供“下载安装助手”入口，再在 `cloud/apps/website-next/` 增加安装助手产品入口；官网入口只指向助手 APK，不在官网复制车机安装流程。
5. 每次 Cloud 侧涉及 release index、R2、官网入口、API 或生产部署时，先读取 Cloud 仓库对应专项文档；部署和上线仍按 Cloud 的固定候选与人工授权规则执行。

## 7. Cloud 路由索引

本轮协议补充：`apps[]` 启用条目必须包含 `versionCode`、`versionName`、`apkSizeBytes`；客户端选择页展示为 `v1.2.3`、`2.6M`，下载后以包名 / 发布者证书完成最小身份校验，并保留实际大小与摘要证据。

在 Cloud 仓库中继续读取：

1. 官网与产品页：`apps/website-next/AGENTS.md`、`docs/architecture/官网静态交互引擎后续施工总纲.md`、`docs/plans/官网Next项目页主链与SticAI复刻施工规范.md`。
2. 通用能力索引：`docs/architecture/9.9Studio通用能力索引.md`。
3. 更新与 release：`docs/protocols/update-contract.md`、`docs/release/release-process.md`、`docs/release/release-profiles.md`、`.agents/skills/release-operations/SKILL.md`。
4. Cloud 运行时与部署：`docs/release/cloud-runtime-release.md`、`docs/operations/domestic-deployment.md`、`docs/plans/staging-production-environments-plan.md`。
5. 产品目录：`products/03lyrics/README.md`、`products/03desktop/README.md`。

上述路径只作为 Cloud 能力入口；03helper 的安装状态机、下载适配器、ADB 网关和 UI 仍以本仓库文档为真值。

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

1. 03helper 需要一个很小的 schema V2 签名配置作为控制面，配置描述当前蓝奏根文件夹、运行时密码、未来历史版本蓝奏文件夹、对应密码、固定组件文件映射和配置版本；包名、证书、APK entry 名称与兼容范围由客户端内置信任清单固定维护，包体版本、大小和摘要由客户端从当前 ZIP 内的 APK 读取。
2. 安装包对象流不经过 03helper 自有服务器。客户端每次从配置取得同一个受密码保护的蓝奏根文件夹，在隐藏 WebView 中完成验证并枚举文件，再按固定文件映射下载实际存在的 ZIP；desktop 必须存在，其他可选 ZIP 可以缺失。根文件夹是唯一的“最新版本”真值，人工替换 ZIP 即可发布更新。
3. R2 / GitHub Releases 不参与当前根文件夹自动版本判断；后续若增加备用对象，必须继续由同一签名配置声明并保持组件版本一致，不能让多个来源各自决定“最新版本”。
4. 600GB/月额度只用于评估对象流量，不用于否定小清单控制面；几 KB 的清单请求不构成 APK 直链流量。

### 3.2 Android 清单适配边界

V2 已冻结独立的 Android distribution-config 控制面。领域对象和签名 envelope 的本地实现位于 `com.tcrrry.helper.domain.artifact` 与 `data/catalog`；这只代表客户端协议已实现，不代表 Cloud 生产配置已经发布。

公共生产入口为 `GET https://api.9.9studio.fun/api/03helper/android-config`，Debug staging 入口为 `GET https://api-staging.9studio.fun/api/03helper/android-config`。envelope 固定包含 `schemaVersion=2`、`configVersion`、`keyId`、`signatureAlgorithm`、`payloadBase64` 和 `signatureBase64`；客户端先对 Base64 解码后的原始 UTF-8 字节验签，再严格解析 payload。payload 固定包含 `schemaVersion=2`、`channel`、`expiresAt`、`folderUrl`、`folderPassword`、`previousVersionsUrl`、`previousVersionsPassword` 和三个固定组件的 `componentId`、`archiveFileName`、`required`。两个密码字段允许空字符串，空字符串表示无密码；`expiresAt=2099-12-31T23:59:59.000Z` 是当前长效发布配置约定。

当前 staging 信任根已轮换为 `keyId=03helper-staging-config-2026-08-22-v1`，算法为 `SHA256withECDSA`、P-256 (`prime256v1`)，客户端内置公钥 SPKI DER SHA-256 为 `8c2573689e87e6c426add9f2249186ec7b6c0e8f44b196ea669c820adb1283d3`。Cloud 只能注入与该指纹匹配的 PKCS#8 私钥；旧 `03helper-real-debug-2026-08-20-v4` 已废弃且不做兼容。配置加密密钥 `ANDROID_CONFIG_ENCRYPTION_KEY_BASE64` 与签名私钥是两套独立材料。

`previousVersionsUrl` 只作为未来人工历史版本入口的签名配置能力准备；当前客户端不把它接入自动更新、版本比较或备用源。非空时必须是 HTTPS 蓝奏文件夹地址，不能包含用户名、密码、query 或 fragment；为空时 `previousVersionsPassword` 必须同时为空。未来历史版本手动选择流程必须另行校验实际 APK 身份，不能复用当前固定三 ZIP 自动更新目录规则。

包名、证书 SHA-256、APK 入口文件名、显示名和最低 SDK 属于 `03helper` 随包内置的组件信任清单，网络 payload 不得覆盖。未知算法、未知 key、字段缺失、过期或固定组件映射不合法均 fail closed。

客户端从实际归档构造的 `ArtifactManifest` 仍包含 `archiveFormat`、`archiveSizeBytes`、`archiveSha256`、`apkEntryName`、`apkSizeBytes`、`apkSha256`、`packageName`、`apkVersion` 和 `certificateSha256`；这些字段不是 Cloud V2 payload 字段，而是本地校验结果与内置信任值的合并结果。

组件配置项只允许固定的三个组件：

1. `desktop`：`03desktop-debug.zip`，唯一必装组件。
2. `lyrics`：`03lyrics-debug.zip`，可选组件。
3. `file-manager`：`fossify-file-manager-car-debug.zip`，可选组件。

配置不得携带任意 shell、脚本、第三方直链转换服务、动态权限或任意包体 URL。Cloud 只提供签名配置和受控文件夹发布能力；发现设备、ADB、安装、授权、解压和运行验证仍由 03helper 自己负责。

### 3.3 Android ZIP 发布流程

1. 各产品仓库先生成已签名 APK，分别由 03 歌词、03桌面和文件管理器仓库负责包身份、版本和证书；03helper 不复制产品源码，也不重新签名。
2. Cloud release 流程为每个组件生成一个 ZIP，归档根目录只放一个预期 APK；服务端只保存固定文件名和必选性，包名、证书和兼容参数由客户端内置清单负责，包体版本、大小和摘要不预先写死。
3. 最多三个 ZIP 上传到同一个受密码保护的蓝奏根文件夹。文件夹只能包含这三个固定 ZIP，desktop ZIP 必须存在，lyrics / file-manager ZIP 可以缺失，不能放 APK、说明文件、重复文件或未知文件；人工替换 ZIP 是唯一发布动作。
4. 客户端每次检查更新重新打开根文件夹，按固定文件映射处理实际存在的 ZIP，逐个下载、解压唯一对应 APK，并读取 APK 版本、大小和 SHA-256 生成本次 `ArtifactManifest`。任一实际组件身份、文件集合或归档结构不符合约束，整次配置拒绝。
5. 只有正式 staging 下载、解压、APK 包身份 / 证书校验和 ADB 安装验证全部通过，才允许把签名配置切到公开状态。当前真实组件 Debug 包只进入 03helper Debug 变体的本地签名验证 profile；它们可以证明客户端完整工程链，但不代表 Cloud production 配置或公开候选通过。
6. 密码只作为签名 payload 的运行时字段下发给客户端；Cloud 不向客户端下发网盘账号、R2 密钥、GitHub PAT 或任意命令，客户端不记录密码和短时下载上下文。

### 3.4 当前 F3 真实 Debug 验证资料

1. Debug 配置的根文件夹地址由服务端签名 payload 提供；客户端只按内置 `archiveFileName` 映射 `03desktop-debug.zip`、`03lyrics-debug.zip` 和 `fossify-file-manager-car-debug.zip`。
2. 实际存在的 ZIP 根目录均只能有一个预期 APK；APK 包名和 Debug 证书由客户端从归档读取并与内置清单比对，版本由 APK 动态读取。Debug 使用完整安装、一次统一授权和可用性主链，不存在安装专用成功态；03桌面是唯一必装核心，其他组件可选。
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

在 Cloud 仓库中继续读取：

1. 官网与产品页：`apps/website-next/AGENTS.md`、`docs/architecture/官网静态交互引擎后续施工总纲.md`、`docs/plans/官网Next项目页主链与SticAI复刻施工规范.md`。
2. 通用能力索引：`docs/architecture/9.9Studio通用能力索引.md`。
3. 更新与 release：`docs/protocols/update-contract.md`、`docs/release/release-process.md`、`docs/release/release-profiles.md`、`.agents/skills/release-operations/SKILL.md`。
4. Cloud 运行时与部署：`docs/release/cloud-runtime-release.md`、`docs/operations/domestic-deployment.md`、`docs/plans/staging-production-environments-plan.md`。
5. 产品目录：`products/03lyrics/README.md`、`products/03desktop/README.md`。

上述路径只作为 Cloud 能力入口；03helper 的安装状态机、下载适配器、ADB 网关和 UI 仍以本仓库文档为真值。

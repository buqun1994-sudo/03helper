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

1. 03helper 需要一个很小的组件发布清单作为控制面，清单只描述组件版本、包身份、兼容范围、ZIP 与 APK 的大小和 SHA-256、证书摘要和来源顺序。
2. 安装包对象流不经过 03helper 自有服务器：国内自动主源使用每个组件独立的无密码蓝奏云 ZIP 分享页，第一备用使用 R2 ZIP 对象，第二备用使用 GitHub Releases ZIP 对象；三个自动来源必须镜像同一 ZIP 字节。密码聚合文件夹、三应用合并 ZIP 和夸克只保留人工或离线兜底。
3. 清单可以由 Cloud 的公开 release index 能力承载，也可以在 Android 发布契约完成前由 03helper 的受控静态入口承载。两种方式都必须保持一个清单真值，不能让三个下载源各自决定“最新版本”。
4. 600GB/月额度只用于评估对象流量，不用于否定小清单控制面；几 KB 的清单请求不构成 APK 直链流量。

### 3.2 Android 清单适配边界

F2 已冻结独立的 Android artifact schema；Cloud Android profile 仍需按此契约在 Cloud 侧落地。领域对象和签名 envelope 的本地实现位于 `com.tcrrry.helper.domain.artifact` 与 `data/catalog`，不代表 Cloud 生产清单已经发布：

```text
schemaVersion
componentId
displayName
required
version { name, code }
compatibility { minAndroidSdk, maxAndroidSdk?, minInstallerVersion?, maxInstallerVersion? }
archiveFormat=zip
archiveFileName
archiveSizeBytes
archiveSha256
apkEntryName
apkSizeBytes
apkSha256
packageName
apkVersion { name, code }
certificateSha256
sources[]
rollbackId
```

清单由 `SignedCatalogEnvelope` 包裹，固定包含 `schemaVersion`、`catalogVersion`、`keyId`、`signatureAlgorithm`、`payloadBase64` 和 `signatureBase64`；客户端先用受信公钥验证 payload 的 detached signature，再严格解码字段。当前实现支持 `SHA256withECDSA` 和运行环境可用时的 `Ed25519`，未知算法、未知 key、字段缺失或摘要 / 版本不合法均 fail closed。

`sources[]` 只允许固定的三类来源：

1. `lanzou-share`：单组件 ZIP 分享页 URL，由 `LanzouWebSourceAdapter` 在进度页面背后用 Android System WebView 的默认手机端标识打开页面并截获当次最终 ZIP 下载地址。
2. `r2`：Cloud R2 的公开 ZIP 对象 URL，由应用自有普通 HTTPS 下载器获取。
3. `github`：GitHub Releases 的公开 ZIP 对象 URL，由应用自有普通 HTTPS 下载器获取。

清单不得携带任意 shell、脚本、第三方直链转换服务、动态权限或运行时任意 URL。Cloud 只提供受审查的清单和对象发布能力；发现设备、ADB、安装、授权、解压和运行验证仍由 03helper 自己负责。

### 3.3 Android ZIP 发布流程

1. 各产品仓库先生成已签名 APK，分别由 03 歌词、03桌面和文件管理器仓库负责包身份、版本和证书；03helper 不复制产品源码，也不重新签名。
2. Cloud release 流程为每个组件生成一个单组件 ZIP，归档根目录只放一个预期 APK；生成后计算 ZIP 大小 / SHA-256 和 APK 大小 / SHA-256，写入 Android profile 的签名清单。
3. 同一 ZIP 字节复制到蓝奏云、R2 和 GitHub Releases，不能分别重新压缩，否则外层 ZIP 摘要会变化。蓝奏云分享页 URL 只作为主源页面地址写入 `sources[]`，不把短时最终地址写入清单。
4. 只有 staging 下载、解压、APK 包身份 / 证书校验、ADB 安装验证和回滚指针全部通过，才允许把清单候选切到公开状态。当前三个测试分享页先作为 fixture，组件映射冻结前不得发布为正式组件；本轮真实 WebView / 网络验证由用户人工主测，未执行项不计为生产通过。
5. Cloud 不向客户端下发压缩包密码、网盘账号、R2 密钥、GitHub PAT 或任意命令；蓝奏云密码聚合文件夹和三应用合并 ZIP 不进入 Android 自动清单。

### 3.4 当前蓝奏云 ZIP fixture

1. `zip-test-1`：`https://wwatl.lanzouw.com/icExq435o4sj`
2. `zip-test-2`：`https://wwatl.lanzouw.com/i9neI435o4zg`
3. `zip-test-3`：`https://wwatl.lanzouw.com/iGrHV435o5ah`
4. 三个地址只用于 03helper 的隐藏 WebView、ZIP 下载和解压施工 fixture；组件映射、ZIP / APK 摘要、包身份和正式发布状态尚未冻结，Cloud release index 在这些信息齐全前不得把它们输出为 production 组件。
5. `2026-08-19` 只读核对 Cloud 当前 `products/`、release profile、release scripts 和协议后，只有 TileLauncher 存在安装包 release profile；03歌词 / 03桌面当前 Cloud 条目是产品或商业配置，没有 03helper Android profile、组件 ZIP 对象、清单公钥或发布脚本。该缺口保持待实施，不由客户端伪造。

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
3. Cloud 现有 TileLauncher 更新协议不能被描述成 Android 组件发布已经完成；必须先完成 Android profile、清单字段、APK 发布脚本和 staging 验证。
4. 官网当前下载链接、Cloud 当前线上版本和 R2 当前对象状态都不能仅凭源码宣称已上线；必须以 Cloud 的 release / deployment 文档和固定候选证据为准。
5. V1 不接账号、支付、权益、Telemetry 或车辆控制；这些能力即使在 Cloud 已存在，也必须由新的产品需求和协议单独授权。

## 6. 后续施工顺序

1. M0：Cloud 侧继续冻结 release index 映射，确认三个产品仓库的包名、版本、签名身份、ZIP entry 名称和发布责任；03helper 本地 schema 已完成。
2. M1：03helper 本地隐藏 WebView 来源适配器、ZIP 下载器、完整性校验和 `InstallationSession` 事件接线已完成；用户提供的三个链接仍只作为 fixture。
3. M2：待 Cloud R2 ZIP 对象和 GitHub Releases 真实候选具备后，执行断点、失败切源、ZIP / APK 双重摘要、回滚和版本一致性人工验证。
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

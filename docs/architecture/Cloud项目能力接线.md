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

## 3. Android 分发唯一主链

1. Cloud 仓库 `docs/protocols/android-helper-config-contract.md` 是 V5 wire 协议真值；本仓库 `docs/protocols/android-helper-config-contract.md` 记录客户端接线。
2. Cloud 的 `AndroidConfigService` 唯一维护目录、启停、required / optional 和批准 APK 身份；03 APP 管家提供产品身份索引及真实 APK 发布资料，不维护客户端第三方准入表。
3. 新客户端固定请求 `GET /api/03helper/android-config?schemaVersion=5`。Debug host 为 `api-staging.9studio.fun`，Release 为 `api.9.9studio.fun`；各自校验签名和环境。
4. staging 配置 keyId 为 `03helper-staging-config-2026-08-22-v1`，SPKI SHA-256 为 `8c2573689e87e6c426add9f2249186ec7b6c0e8f44b196ea669c820adb1283d3`；production 为 `03helper-production-config-2026-08-30-v1` 和 `a971be7085a2a4a3ef8df8dd2b9df5a94b46e05b3ce85b934ffc84e42ce70051`。两者沿用 P-256 / SHA256withECDSA，私钥和密码加密密钥不进入客户端。
5. V5 只保留目录、动态 apps 和必要签名 metadata；删除历史目录、icon、description、deviceSetup、trustProfileId、逐 APP schema 门槛及重复 channel / catalogVersion。
6. 客户端先验原始 payload 字节，再将批准的 packageName、certificateSha256s、apkVersion、apkSizeBytes、apkSha256 传入唯一制品准备 owner。缓存和下载都要精确匹配；当前完整签名集合不是任意单证书命中。
7. 本地 `ArtifactManifest` 继续记录 archiveFormat、archiveSizeBytes、archiveSha256、apkEntryName 和 APK 核验结果；历史单 certificateSha256 只用于旧持久化模型兼容，新批准身份以完整集合为准。最低 SDK、权限和服务声明从 APK 读取。
8. Cloud 决定 required / optional，没有固定 desktop 必装要求；本地 `AuthorizationPlanFactory.requiresLaunchVerification` 仅为已知桌面包启动验证。普通第三方可完成空授权计划，任意确认已安装应用可进入维护。
9. 维护“管理已安装应用”继续消费 03桌面协议 v2 只读桥接的完整非系统库存，与下载目录用途分离；桥接不下发控制命令。缺少该桥接只影响这项专属能力。
10. 03helper 免费且独立，正式包名 / namespace 为 `com.ninepointnine.helper`，测试 applicationId 加 `.test`；版本以根 `release-version.properties` 为准，身份索引由共享 03 APP 管家维护。
11. 发布资料由 Cloud `android-config:prepare` 从真实 APK 提取；先上传单 APK ZIP 并核验，再启用配置。未知应用初始通用图标，核验后提取 APK 图标。Logo CDN 不再是 Android V5 门槛。
12. 本轮部署由 Windows 开发机完成。先部署 V5 服务和 SQL 033，再运行 Cloud `android-config:migrate:v5` 转换活动配置；旧公开入口只读冻结历史。详细交接见 Cloud `docs/operations/android-config-v5-migration.md`。本地通过不代表环境已迁移。

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

## 6. 后续施工与 Cloud 路由

1. 部署与迁移：Cloud `docs/release/cloud-runtime-release.md`、`docs/operations/android-config-v5-migration.md`；部署仍从固定 SHA 构建全栈候选、staging 验收、production 晋升同一候选。
2. APK 目录：Cloud `docs/protocols/android-helper-config-contract.md`、`.agents/skills/03-app-manager/SKILL.md`；配置日常变更不构建 Android candidate、不重复部署运行时。
3. 官网产品页：Cloud `apps/website-next/AGENTS.md`、`docs/architecture/官网静态交互引擎后续施工总纲.md`、`docs/plans/官网Next项目页主链与SticAI复刻施工规范.md`。
4. 通用能力与桌面 release：Cloud `docs/architecture/9.9Studio通用能力索引.md`、`docs/protocols/update-contract.md`、`docs/release/release-process.md`、`docs/release/release-profiles.md`。
5. Cloud 源码和本地验证不能证明公网已上线。各环境 V5 签名读回、真实 ZIP 下载和车机安装须分别留证，结果见 `docs/progress.md`。

上述路径只作为 Cloud 能力入口；03helper 的安装会话、下载适配器、ADB 网关和 UI 仍由本仓库负责。

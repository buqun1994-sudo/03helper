# Android 分发 V5 施工方案

状态：本地施工、验证与最终产物交付完成；环境部署、配置转换和真实下载验收由 Windows 开发机接手，未执行。

## 1. 目标与授权

1. 云端是应用分发准入的唯一 owner；客户端核验签名配置和对应 APK 并执行用户选择。
2. 本轮包括 03helper、Cloud API 与 Android 下载后台、共享 03 APP 管家规则、相关检查及文档。
3. 用户最初授权修改 staging 与 production 数据库和配置，随后明确本任务不处理部署、部署只能在 Windows 开发机完成。本任务交付代码、真实 APK 资料和迁移命令；Windows 先部署对应运行时，再执行各环境配置转换。未修改远端数据库。
4. 不把本地测试、fixture 或已生成 APK 写成真实云端与车机闭环通过。

## 2. 契约

1. V5 envelope 仅包含 `keyId`、`signatureAlgorithm`、`payloadBase64`、`signatureBase64`；先验证原始 UTF-8 payload 字节，再解析。
2. payload 包含 `schemaVersion=5`、`environment`、`catalogRevision`、`issuedAtUtc`、`expiresAt`、`folderUrl`、`folderPassword`、`apps`。
3. APP 条目包含 `appId`、`archiveFileName`、`displayName`、`enabled`、`sortOrder`、`installPolicy`、`versionCode`、`versionName`、`apkSizeBytes`、`packageName`、`certificateSha256s`、`apkSha256`。
4. 删除当前协议的历史目录、远程图标、说明、云端授权选择器、本地发布者档案 ID、逐条 schema 门槛。channel 由 environment 派生；展示修订标签由 catalogRevision 派生。
5. 保留现有 P-256 配置密钥和环境隔离；payload 上限 512 KiB、APK 上限 1 GiB、文件名上限 128 UTF-8 字节、单 ZIP 根目录单 APK、签名摘要为 64 位十六进制。
6. APK 包名、完整当前签名证书集合、版本和 SHA-256 必须与批准记录相符；缓存与远端 APK 使用同一校验 owner。证书集合比较不接受任意单个命中。
7. `installPolicy` 仅取 `required` 或 `optional`。初始发布保持 desktop required，其余 optional；客户端不依靠固定 APP ID 决定分发准入和必选性。
8. 未启用条目允许作为草稿保存，但不得下载或安装。签名或环境错误拒绝整个配置；文件错误隔离到单个 APP。

## 3. 物理锚点

1. Cloud `apps/telemetry-api-domestic/src/android-config-service.js`：`normalizeConfigInput`、`normalizeApps`、`upsertConfig`、`readSignedEnvelope`、`buildPayload`、`formatAdminConfig`，继续作为唯一配置 owner。
2. Cloud `apps/telemetry-api-domestic/src/android-config-router.js`：公共读取按 `schemaVersion=5` 选择新版，缺省旧入口只读冻结历史；后台 PUT 只写 V5。
3. Cloud `apps/03lyrics-admin/public/app.js` 与现有 HTML/CSS：移除废弃表单，增加发布信息导入与只读身份显示。
4. Cloud 新迁移 `apps/telemetry-api-domestic/migrations/033_android_helper_config_schema_v5.sql`：来源 `022_android_helper_config_schema_v4.sql`，所属 Android config PostgreSQL 主链，无 Kotlin 类型或命名空间；保留可回滚代码需要的旧列，旧快照冻结后不再编辑。
5. 03helper `data/catalog/InstallerDistributionConfig.kt`：V5 wire DTO 与 `CloudInstallerDistributionConfigAdapter`；文件头直接沿用原文件 `com.ninepointnine.helper.data.catalog`。
6. 03helper `application/artifact/ArtifactPreparationCoordinator.kt`、`data/artifact/ArtifactIdentityVerifier.kt`：云端批准身份和文件摘要的单一核验链。
7. 03helper `domain/session/InstallationSession.kt`、`domain/device/DeviceActions.kt`：选择、授权空计划、安装后证据与维护行为；沿用既有状态机和强类型动作。
8. 03helper `application/maintenance/MaintenanceSessionStore.kt`：迁移旧维护记录，保留用户状态；旧缓存需重新匹配 V5 批准记录。
9. 发布信息提取沿用 Cloud `scripts/lib/03app-staging-package.mjs` 中 aapt/apksigner/ZIP 读取能力；第三方 APK 不重签，不进入 03 APP 产品身份矩阵。
10. 本文来源为 `docs/protocols/android-helper-config-contract.md` 与已确认方案；UTF-8 文档，无代码命名空间。
11. Cloud `apps/telemetry-api-domestic/scripts/migrate-android-config-v5.mjs`：文件头与 ESM 命令入口来自同目录 `seed-admin-operator.mjs`；通过现有 `readConfig` 与 `AndroidConfigService` 完成预览、修订号锁内检查、事务升级和读回，不维护第二套 SQL 写入逻辑。
12. `InstallerRuntime.launchCatalog` 在签名目录进入会话后调用 `MaintenanceController.inspectInitialApplications`；库存只查询当前目录的包名，`InstallationSession.handleInitialInstalledApplicationsResolved` 校验与当前目录完全对应。原固定 03 库存入口删除，未知已安装 APP 与自有产品共用同一流程。
13. Cloud 新 `docs/operations/android-config-v5-migration.md` 来源为 Cloud 协议、候选发布文档与上述迁移命令；UTF-8 文档，无代码命名空间，统一承载 Windows 操作顺序与环境验收。

## 4. 迁移与验证

1. 先实现并验证 V5，保存旧环境的加密配置快照，旧公开入口保持旧字段与旧包列表。
2. V5 客户端只请求显式新版入口，不自动降回本地白名单。旧客户端须升级后才能使用新目录。
3. 新目录的 APK 信息必须从真实发布物提取，不能由文件名猜测或用本地信任表填充。
4. schema 迁移、配置写入、签名输出、反回滚和幂等性必须通过服务端定向测试；双方消费同一签名 fixture 的契约测试必须通过。
5. 客户端必须通过相关 JVM 测试、Debug/Release 编译、Debug Lint、Debug APK 和现有低成本运行 smoke；最后保留数据覆盖安装并核对包身份。
6. 后台需要实际浏览器检查导入、编辑、保存、错误与布局；真实数据库迁移前后核对每个环境的目录、身份和签名。
7. 验证未知发布者、同版本错误 APK、缓存匹配、单项失败、必选策略、更新、自更新、维护恢复和合法零授权动作。
8. 最终验收：固定新版助手后，仅在 Cloud 新增此前未知的第三方应用即可下载和安装。真实 ZIP 或设备不可达时记录精确阻断，不宣称真实通过。

## 5. 施工进度

- [x] 读取两端源码、用户确认方案和授权。
- [x] 固定 V5 字段与物理锚点。
- [x] Cloud API、迁移命令、后台和发布信息导入。
- [x] 客户端协议、文件核验、选择、动态库存与维护恢复。
- [x] 共享规则、文档和检查同步。
- [x] 自动化、构建、后台浏览器与客户端入口运行检查。
- [x] staging 与 production 操作命令及 Windows 交接文档；实际部署、转换和公网验收由 Windows 接手，未执行。
- [x] 最终可测试客户端、状态与剩余验收交付；版本 `1.0.13 (14)`，实机手机离线，已完成本机模拟器验证。

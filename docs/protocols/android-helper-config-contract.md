# Android 助手动态分发配置契约 V5

本文件记录客户端接线。完整 wire 字段、Cloud 后台和数据升级的唯一协议真值是 Cloud 仓库 `docs/protocols/android-helper-config-contract.md`；位置从本仓库 `docs/architecture/Cloud项目能力接线.md` 路由。

## 1. 职责与入口

1. Cloud 配置服务决定可分发应用、启停、必选策略以及批准的 APK 身份；03helper 只核验并执行。客户端没有发布者证书表、包名前缀准入或逐应用信任档案。
2. Debug 请求 staging，Release 请求 production，固定路径 `GET /api/03helper/android-config?schemaVersion=5`。新客户端只接受 V5；旧协议仅由 Cloud 冻结入口服务旧客户端。
3. envelope 只有 keyId、signatureAlgorithm、payloadBase64、signatureBase64。先验证原始 payload 字节，再严格解析 schema、环境、时间和应用字段。
4. 继续使用现有独立的 staging / production P-256 配置公钥。APK 发布证书由签名 payload 批准；配置公钥与 APK 证书职责不同。

## 2. 唯一执行链

1. `CloudInstallerDistributionConfigAdapter` 生成携带包名、完整当前证书集合、APK SHA-256、versionCode、versionName 和字节数的 `InstallerComponentSource`。
2. `ArtifactCatalogSessionAdapter` 将云端 enabled、sortOrder 和 required / optional 策略传入已有会话；运行时随后按当前目录包名查询车机库存，已安装第三方同样不可重复选择。无固定 desktop 必装要求，也不从本地维护记录补充下载目录。
3. `ArtifactPreparationCoordinator` 优先匹配公共 Download，再按声明文件名解析蓝奏 ZIP。两条来源必须匹配同一批准身份、版本、大小和摘要；根目录单 APK、1 GiB 上限和路径校验沿用现有适配器。
4. 准备结果进入原 `ArtifactManifest`、设备安装和维护状态机；完整证书集合必须精确相等，额外签名人和同版本不同文件都不能通过。
5. `KnownApplicationPackages` 仅识别本地已实现能力的包名别名，不是分发清单。未知第三方可以安装；没有对应专属能力时不套用桌面动作，合法空授权计划成功。
6. 单应用失败隔离，余下应用继续；任意已确认安装的应用可进入维护。Cloud 下架不会卸载已安装应用。
7. APK 图标只从已经核验的文件提取并绑定批准摘要；未知 APP 下载前使用通用图标，自有内置图标继续作为展示资源。

## 3. 协议边界

1. payload 包含 schemaVersion=5、environment、catalogRevision、issuedAtUtc、expiresAt、folderUrl、folderPassword、apps。环境派生频道，修订号派生显示标签，不重复在线传输。
2. APP 字段为 appId、archiveFileName、displayName、enabled、sortOrder、installPolicy、versionCode、versionName、apkSizeBytes、packageName、certificateSha256s、apkSha256。
3. V5 不接收 description、icon、deviceSetup、trustProfileId、minClientSchemaVersion、历史目录或重复的 channel / catalogVersion。未知字段拒绝。
4. payload 最大 512 KiB，APK / ZIP 最大 1 GiB，文件名和名称最多 128 UTF-8 字节，密码最多 512 字节，版本名最多 64 字节；证书集合 1..16 个不重复 SHA-256。
5. 停用草稿不展示、不下载、不安装。授权动作仍由本地强类型能力编译器生成，读取原值并回读，不能由 Cloud 下发 shell 或扩大系统权限。
6. 反回滚存储按环境和频道保存最高 revision 及原始 payload SHA-256。低修订或相同修订不同字节拒绝，修订持久化失败也拒绝。
7. 密码仅用于本次加载与目录请求，不写日志、维护基线或持久缓存。

## 4. 升级与验收

1. 先部署支持 V5 的 Cloud 服务和数据库迁移，再通过真实 APK 发布资料转换配置，最后交付新客户端。部署及环境数据操作由 Windows 开发机负责。
2. Cloud 旧入口保留升级前签名 payload；新应用只进入 V5。用户必须升级助手才能取得 V5 新目录，不提供本地白名单回退。
3. 共享契约样本位于 `app/src/test/resources/android-config-v5-contract.json`，由 Cloud 配置服务生成，测试验证公钥、原始字节和未知发布者的完整证书集合。
4. 客户端验证包括签名、未知字段、环境、时间、反回滚、缓存 / 远端 APK 全字段匹配、单项失败、云端必选策略、维护恢复、自更新和空授权计划。
5. 构建、运行及剩余真实链路见 `docs/testing/验证矩阵.md`；施工与授权记录见 `docs/plans/Android分发V5施工方案.md`。

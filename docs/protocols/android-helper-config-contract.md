# Android 助手动态分发配置契约

## 1. 入口与信任边界

客户端固定请求 `GET /api/03helper/android-config`。响应是 detached-signature envelope：客户端先对 `payloadBase64` 解码后的原始 UTF-8 字节验签，再解析 payload。当前客户端只接受 `schemaVersion = 3`。

构建变体与环境必须成对匹配：Debug 只接受 `environment=staging`、`channel=debug`；Release 只接受 `environment=production`、`channel=release`。其它组合在目录枚举前拒绝。

Cloud 声明目录、应用展示元数据、APK 的 `versionCode`、`versionName`、`apkSizeBytes` 以及受限的本地信任 / 授权档案 ID；不声明或覆盖 APK 的 `packageName`、`certificateSha256`、`minAndroidSdk`。客户端在选择页直接展示这三个版本 / 包体字段，下载后仍从实际 APK 读取包名、版本、大小、证书和 SHA-256，并逐项与 Cloud 字段核对；现有内置 `appId` 仍额外绑定其本地官方包名，未知 `appId` 只能落在受信发布者命名空间内。

## 2. v3 envelope 与 payload

envelope 与解码后的 payload 都携带同一 `catalogVersion`、`catalogRevision`；两者不一致时在目录枚举前拒绝。

```json
{
  "schemaVersion": 3,
  "catalogVersion": "android-debug-2026-08-22-001",
  "catalogRevision": 1,
  "keyId": "03helper-staging-config-2026-08-22-v1",
  "signatureAlgorithm": "SHA256withECDSA",
  "payloadBase64": "...",
  "signatureBase64": "..."
}
```

`payloadBase64` 解码后：

```json
{
  "schemaVersion": 3,
  "environment": "staging",
  "channel": "debug",
  "issuedAtUtc": "2099-01-01T00:00:00Z",
  "expiresAt": "2099-12-31T23:59:59Z",
  "catalogVersion": "android-debug-2026-08-22-001",
  "catalogRevision": 1,
  "folderUrl": "https://wwatl.lanzouw.com/bexample",
  "folderPassword": "...",
  "previousVersionsUrl": "",
  "previousVersionsPassword": "",
  "apps": [
    {
      "appId": "desktop",
      "archiveFileName": "03desktop-debug.zip",
      "displayName": "03桌面",
      "description": "车机桌面应用",
      "versionCode": 123,
      "versionName": "1.2.3",
      "apkSizeBytes": 4567890,
      "enabled": true,
      "installPolicy": "required",
      "sortOrder": 10,
      "minClientSchemaVersion": 3,
      "trustProfileId": "nine-studio",
      "deviceSetup": {
        "profileId": "",
        "actionIds": []
      }
    }
  ]
}
```

`appId` 是 Cloud 目录标识，不是 Android 包名。`archiveFileName` 必须是单一 ZIP 文件名，禁止目录、查询参数、片段和路径穿越。`displayName`、`description`、`versionCode`、`versionName`、`apkSizeBytes` 都是受签名保护的配置字段；客户端在连接后的轻量选择阶段不下载 ZIP，直接把 `versionName` 规范化为 `V1.2.3` 形式，并把 `apkSizeBytes` 规范化为紧凑单位（例如 `2.6M`）。`versionCode` 是正整数，`versionName` 为非空 Android 版本名，`apkSizeBytes` 为正的 APK 本体字节数。用户确认后，实际 APK 的包名、版本名、版本号、文件大小、SHA-256 和证书必须与配置一致；任何不一致只记录该 APP 失败。`enabled=false` 的条目不显示、不下载、不安装。启用条目按 `sortOrder` 排序，平局按 `appId`。除 `desktop` 外，`installPolicy=required` 只表示首次安装建议，不代表整个目录必须包含该 APP；`desktop` 必须同时 `enabled=true` 且 `installPolicy=required`，它是首次安装唯一核心必装项。

旧协议中的 `versionLabel`、`sizeLabel` 不再作为 Cloud 字段；客户端不读取它们，也不把 `catalogVersion` 或蓝奏 ZIP 行大小当作应用版本 / APK 大小。`catalogVersion`、`catalogRevision` 仅用于配置快照的防回滚和内部追踪。

客户端不设置业务 APP 数量上限；只执行传输体和字段长度护栏：payload 最大 512 KiB，`appId` 最大 64 字节，文件名最大 128 字节，名称最大 128 字节，描述最大 512 字节。

## 3. 目录处理

客户端重新枚举受保护蓝奏云目录，只处理已启用 `apps[]` 中声明的 ZIP。未声明文件（包括说明文件、旧 ZIP 和其它人工文件）直接忽略。目录枚举只负责建立远端定位结果，不是安装包可用性的唯一门槛：用户确认后，每个选中 APP 先扫描手机公共 `Download`，找到通过包名、`versionCode`、`versionName`、`apkSizeBytes`、哈希和受信证书校验的 APK 就直接复用；本地未命中时才解析并下载对应 ZIP。声明 ZIP 缺失（包括 `desktop`）只生成该 APP 的结构化失败，后续应用继续处理；全部结束后由结果页决定是否可进入维护。

每个 ZIP 独立下载、校验和解压。解压根目录必须只有一个 APK，不允许子目录、第二个文件或说明文件；APK 必须能读取包名、版本、大小、SHA-256 和证书，并与配置的 `versionCode`、`versionName`、`apkSizeBytes` 一致。已知包名不匹配或 `deviceSetup` 绑定到其它包名时，只更新该 APP 的失败状态，不阻塞其它 APP。

## 4. 授权声明

`deviceSetup` 在线格式只包含客户端已知的 `profileId` 与 `actionIds`；客户端先将其编译为版本化 `AuthorizationAction`，再落到本地强类型 AppOps、运行时权限、安全设置和安全组件列表项。远端内容不能携带 shell 文本、包身份或任意设置名。执行前读取原值，计算去重后的最终列表并进行 Android 9 容量预检，写入后回读验证并保留已有条目。

## 5. 版本与回滚

客户端按 `environment + channel` 持久化已接受的最高 `catalogRevision`。低于该修订号的签名快照拒绝使用；有意回滚必须由 Cloud 发布一个新的修订号指向旧目录。配置内容不变时 Cloud 不应生成新修订号。

发布顺序固定为：创建不可变目录并上传全部 ZIP，完成外部 ZIP/APK 预检，再发布签名配置。禁止原地覆盖同名 ZIP。

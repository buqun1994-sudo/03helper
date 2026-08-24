---
name: 03-app-repository
description: 03 APP 客户端仓库入口；在 03helper 的包名、namespace、版本、签名、远端或分发产物任务中调用共享的 03 APP 管家规则。
---

# 03 APP 仓库入口

本仓库登记为 `productId=03helper`。这是路由 skill，不复制身份规则正文。

1. 涉及包名、namespace、版本号、签名摘要、许可证、远端仓库、构建产物、后台导出包或发布前检查时，先定位共享 Cloud 仓库（优先环境变量 `THREE_APP_CLOUD_ROOT`，其次同级目录 `../cloud`），加载 `<cloud-root>/.agents/skills/03-app-manager/SKILL.md`（可用 `$03-app-manager` 调用）。
2. 共享登记库 `products/03app/registry.json` 是跨仓库索引；本仓库的 `app/build.gradle.kts`、`release-version.properties` 和 `products/03helper/android-identity.json` 仍是各自领域真值。发现冲突时报告冲突，不自行改写另一方。
3. 本产品固定包名和 namespace 为 `com.ninepointnine.helper`，`licenseMode=none`；可以分发其它 03 APP，但不拥有它们的包名、证书或许可证身份，公共配置 payload 不得下发 `packageName` 或 `certificateSha256`。
4. 变更完成后运行 `node scripts/check-03app-repository.mjs`，再运行本仓库直接相关的项目检查。签名材料、JKS、口令、token 和本机绝对路径不得写入 Git。

共享规则只允许从 Cloud 管家读取；不要在本仓库创建第二份 03 APP 登记表或复制包名矩阵。

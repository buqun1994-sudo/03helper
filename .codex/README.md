# Codex 项目配置

## 1. 用途

1. `config.toml` 保存可提交的项目级 Codex 配置。
2. `local-context.properties.example` 声明本项目会使用的本机配置键，不包含个人路径、设备地址或密钥。
3. `local-context.properties` 只保存当前电脑的 SDK、ADB、独立测试设备和可选工具链路径，已由 `.gitignore` 排除。

## 2. 边界

1. 项目共同规则写入根 `AGENTS.md` 和 `docs/`。
2. 高频动态工作流写入 `.agents/skills/`。
3. 个人偏好、私有路径、设备地址和凭证只放本机上下文，不得复制到提交、日志或对外文档。
4. 需要构建或设备能力时，先执行 `node scripts/check-local-environment.mjs`；该检查只验证本机配置，不替代安装流程 smoke。

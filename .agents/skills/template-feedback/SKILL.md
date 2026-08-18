---
name: template-feedback
description: 当 03helper 中已验证的规则、Skill、检查脚本、文档槽位或工作流适合多个新项目默认复用，需要去除项目专有信息后回流本机 NewProject 模板源时使用。
---

# Template Feedback

## 1. 目标

把跨项目通用能力去专有化并回流本机 `NewProject`，同时让 `03helper` 只保留自己的项目事实和回流记录。

## 2. 必读

1. 当前项目根 `AGENTS.md`、相关规则和 `docs/progress.md`。
2. 本机上下文中声明的模板源位置（若未配置则停止并记录阻断）。
3. 模板源根 `AGENTS.md` 和本次目标文件。

## 3. 回流门禁

1. 先检查模板源 Git 状态；存在未提交改动时必须理解并保留，不能覆盖。
2. 项目名称、包名、源码路径、设备型号、坐标、业务协议、发布入口和历史兼容留在 `03helper`。
3. 只回流工作流骨架、通用规则、结构检查、文档槽位和跨项目验证原则。
4. 无法证明跨项目通用时，只在 `03helper` 记录“模板候选”，不修改模板源。
5. 未经用户明确要求，不提交或推送模板源。

## 4. 验证

1. 模板源执行其根 `AGENTS.md` 规定的模板和 Skill 检查。
2. 当前项目执行 `node scripts/check-project-docs.mjs`、`node scripts/check-skills.mjs` 和 `git diff --check`。

## 5. 交付

说明回流文件、去除的项目专有信息、两边验证结果和提交状态；未回流时说明客观原因与建议落点。

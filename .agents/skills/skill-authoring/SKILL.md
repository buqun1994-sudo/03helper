---
name: skill-authoring
description: 当需要创建、更新、拆分或审查 03helper 仓库内 `.agents/skills` 的项目级 Skill，并同步路由、验证和进度记录时使用。
---

# Skill Authoring

## 1. 先判断

1. 一次性事实写入 `docs/progress.md`，不创建 Skill。
2. 固定机械检查优先写脚本、测试或 checklist。
3. 只有需要 AI 根据上下文动态选择步骤且预计重复使用的流程才创建 Skill。
4. 跨项目通用的能力先用 `template-feedback` 评估模板回流。

## 2. 必读

1. 根 `AGENTS.md`。
2. `docs/README.md`。
3. `docs/architecture/rules/README.md`。
4. 现有 `.agents/skills/*/SKILL.md`。
5. 本轮任务直接相关的项目文档。

## 3. 创建或更新

1. 名称只用小写字母、数字和连字符，目录名与 frontmatter `name` 一致。
2. `SKILL.md` frontmatter 只保留 `name` 和清晰的触发 `description`。
3. 正文保持精简，只写必读、判断、步骤、边界、验证和交付。
4. `agents/openai.yaml` 至少包含 `display_name`、`short_description` 和显式提及 `$skill-name` 的 `default_prompt`。
5. 不在 Skill 中创建 README、安装指南、变更日志或重复项目文档。
6. 同步根 `AGENTS.md`、`docs/README.md`、验证矩阵和进度记录中的路由。

## 4. 验证

1. 执行 `node scripts/check-skills.mjs`，校验目录、frontmatter 和 `agents/openai.yaml` 的项目约束。
2. 如当前 Codex 安装提供 `skill-creator` 的 `quick_validate.py`，可将它作为外部复核；它不是项目运行依赖。
3. 执行 `node scripts/check-project-docs.mjs` 和 `git diff --check`。

## 5. 交付

说明 Skill 的触发场景、为什么不能由一次性文档或机械脚本承接、同步的路由和验证结果。

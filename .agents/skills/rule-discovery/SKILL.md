---
name: rule-discovery
description: 当 03helper 施工中发现可复用规则、重复失败、稳定的代码 / 设计 / 产品 / 验证 / 安全 / 运维 / AI 协作约束，或发现适合所有新项目的模板级能力候选时使用。
---

# Rule Discovery

## 1. 目标

把跨任务复用的经验沉淀为规则、测试、脚本或 Skill，减少后续对话重复推理；项目专有事实留在 `03helper`，跨项目能力去专有化后回流 `NewProject`。

## 2. 必读

1. 根 `AGENTS.md`。
2. `docs/README.md`。
3. `docs/architecture/rules/README.md` 和命中的分类文件。
4. 本轮直接相关的产品、架构、验证或安全文档。

## 3. 判定

1. 一次性状态或本轮结果：写入 `docs/progress.md`，不创建规则。
2. `03helper` 专有事实：写入产品、架构、验证、安全或运维文档。
3. 跨任务稳定经验：写入 `docs/architecture/rules/` 的正确分类。
4. 固定机械动作：优先补脚本或测试。
5. 需要根据上下文选择步骤的重复流程：再考虑项目级 Skill。
6. 跨多个新项目通用：记录候选，并使用 `template-feedback` 去专有化回流模板源。

## 4. 写入要求

每条规则写清触发条件、应采取动作、验证方式和适用边界。禁止复制其它项目的名称、路径、域名、业务协议、部署入口和历史兼容。

## 5. 验证与交付

1. 执行 `node scripts/check-project-docs.mjs`。
2. 涉及 Skill 时执行 `node scripts/check-skills.mjs`。
3. 执行 `git diff --check`。
4. 说明沉淀位置、复用理由、机械承接方式和是否已回流模板源。

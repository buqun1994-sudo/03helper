#!/usr/bin/env node

import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const failures = [];

const requiredFiles = [
  "AGENTS.md",
  ".gitignore",
  ".codex/README.md",
  ".codex/config.toml",
  ".codex/local-context.properties.example",
  "docs/README.md",
  "docs/来源锚点.md",
  "docs/architecture/项目长期总纲.md",
  "docs/architecture/Cloud项目能力接线.md",
  "docs/product/产品需求基线.md",
  "docs/plans/03helper首版安装流程计划.md",
  "docs/plans/V1应用功能与UI施工文案.md",
  "docs/testing/验证矩阵.md",
  "docs/operations/本地开发环境.md",
  "docs/security/安全与密钥边界.md",
  "docs/plans/codex-task-intake-template.md",
  "docs/progress.md",
  "docs/architecture/rules/README.md",
  "docs/architecture/rules/code.md",
  "docs/architecture/rules/design.md",
  "docs/architecture/rules/product.md",
  "docs/architecture/rules/testing.md",
  "docs/architecture/rules/security.md",
  "docs/architecture/rules/operations.md",
  "docs/architecture/rules/ai-collaboration.md",
  "scripts/check-skills.mjs",
];

function read(relativePath) {
  const path = join(root, relativePath);
  if (!existsSync(path)) {
    failures.push(`缺少文件：${relativePath}`);
    return "";
  }
  try {
    return readFileSync(path, "utf8");
  } catch (error) {
    failures.push(`${relativePath} 不是有效 UTF-8：${error.message}`);
    return "";
  }
}

const contents = new Map(requiredFiles.map((file) => [file, read(file)]));
const agents = contents.get("AGENTS.md") || "";
const docsIndex = contents.get("docs/README.md") || "";
const architecture = contents.get("docs/architecture/项目长期总纲.md") || "";
const cloudIntegration = contents.get("docs/architecture/Cloud项目能力接线.md") || "";
const product = contents.get("docs/product/产品需求基线.md") || "";
const uiPlan = contents.get("docs/plans/V1应用功能与UI施工文案.md") || "";
const ignore = contents.get(".gitignore") || "";

if (!agents.includes("03helper")) failures.push("AGENTS.md 未写入项目名称 03helper");
if (!docsIndex.includes("docs/architecture/项目长期总纲.md")) {
  failures.push("docs/README.md 缺少架构总纲路由");
}
for (const route of [
  "docs/product/产品需求基线.md",
  "docs/testing/验证矩阵.md",
  "docs/operations/本地开发环境.md",
  "docs/security/安全与密钥边界.md",
  "docs/progress.md",
  ".agents/skills/rule-discovery/SKILL.md",
]) {
  if (!docsIndex.includes(route)) failures.push(`docs/README.md 缺少路由：${route}`);
}

if (!product.includes("单组件 ZIP")) failures.push("产品基线未固定单组件 ZIP 主链");
if (!architecture.includes("ArtifactArchiveExtractor")) {
  failures.push("架构总纲缺少 ArtifactArchiveExtractor owner");
}
for (const field of [
  "archiveFormat",
  "archiveSizeBytes",
  "archiveSha256",
  "apkEntryName",
  "apkSizeBytes",
  "apkSha256",
  "packageName",
  "apkVersion",
  "certificateSha256",
]) {
  if (!cloudIntegration.includes(field)) failures.push(`Cloud Android 清单缺少字段：${field}`);
}
if (!uiPlan.includes("隐藏 WebView")) failures.push("V1 UI 施工文案未声明隐藏 WebView 用户边界");
if (!uiPlan.includes("#1976C5")) failures.push("V1 UI 施工文案未保留浅蓝主色令牌");
if (!uiPlan.includes("大号分类按钮")) failures.push("V1 UI 施工文案未固定维护态大号分类按钮");
if (!uiPlan.includes("FastOutSlowInEasing")) failures.push("V1 UI 施工文案未固定基础非线性动效");

for (const marker of [
  ".codex/local-context.properties",
  "local.properties",
  "keystore.properties",
  "*.jks",
  "*.keystore",
  "*.apk",
]) {
  if (!ignore.includes(marker)) failures.push(`.gitignore 缺少排除项：${marker}`);
}

for (const [file, content] of contents) {
  // 个人绝对路径和常见密钥文件名不得进入可提交底座；来源锚点中的项目名是有意记录。
  if (/(?:\/Users\/[^/]+\/|[A-Za-z]:\\\\Users\\\\[^\\]+\\\\)/.test(content)) {
    failures.push(`${file} 写入了个人绝对路径`);
  }
  if (/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/.test(content)) {
    failures.push(`${file} 写入了私钥内容`);
  }
}

if (failures.length > 0) {
  console.error("项目文档检查失败：");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("项目文档检查通过。");

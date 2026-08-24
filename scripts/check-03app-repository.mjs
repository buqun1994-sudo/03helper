#!/usr/bin/env node

import { existsSync } from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import process from "node:process";
import { fileURLToPath } from "node:url";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const cloudRoot = path.resolve(process.env.THREE_APP_CLOUD_ROOT || path.join(projectRoot, "..", "cloud"));
const guard = path.join(cloudRoot, "scripts", "guards", "check-03app-registry.mjs");

if (!existsSync(guard)) {
  console.error(`找不到共享 03 APP 管家 Guard：${guard}`);
  console.error("请设置 THREE_APP_CLOUD_ROOT，或将 cloud 仓库放在本仓库同级目录。");
  process.exit(1);
}

const result = spawnSync(process.execPath, [guard, ...process.argv.slice(2)], {
  cwd: cloudRoot,
  stdio: "inherit",
  env: process.env,
});
process.exit(result.status ?? 1);

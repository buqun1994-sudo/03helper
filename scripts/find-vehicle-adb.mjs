#!/usr/bin/env node

import { existsSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const cloudRoot = path.resolve(process.env.THREE_APP_CLOUD_ROOT || path.join(projectRoot, "..", "cloud"));
const entry = path.join(cloudRoot, "scripts", "find-vehicle-adb.mjs");

if (!existsSync(entry)) {
  console.error(`找不到 03 APP 共享车机发现入口：${entry}`);
  console.error("请设置 THREE_APP_CLOUD_ROOT，或将 cloud 仓库放在本仓库同级目录。");
  process.exit(1);
}

const result = spawnSync(
  process.execPath,
  [entry, `--project-root=${projectRoot}`, ...process.argv.slice(2)],
  { cwd: projectRoot, stdio: "inherit", env: process.env },
);
process.exit(result.status ?? 1);

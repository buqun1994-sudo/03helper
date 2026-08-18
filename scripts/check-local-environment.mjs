#!/usr/bin/env node

import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const configPath = join(root, ".codex", "local-context.properties");
const failures = [];
const notes = [];

function parseProperties(content) {
  const values = {};
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator < 1) continue;
    values[line.slice(0, separator).trim()] = line.slice(separator + 1).trim();
  }
  return values;
}

function run(label, command, args) {
  const result = spawnSync(command, args, { cwd: root, encoding: "utf8" });
  if (result.status !== 0) {
    const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
    failures.push(`${label} 失败：${output || "无输出"}`);
    return "";
  }
  return `${result.stdout || ""}${result.stderr || ""}`.trim();
}

if (!existsSync(configPath)) {
  console.error("缺少 .codex/local-context.properties；请参考同目录 example 创建本机配置。");
  process.exit(1);
}

const config = parseProperties(readFileSync(configPath, "utf8"));
for (const key of ["JDK17_HOME", "ANDROID_SDK_ROOT", "PRIMARY_ADB"]) {
  if (!config[key]) failures.push(`本机配置缺少 ${key}`);
}

if (config.JDK17_HOME) {
  const java = join(config.JDK17_HOME, "bin", "java");
  if (!existsSync(java)) failures.push("JDK17_HOME 下找不到 bin/java");
  else {
    const version = run("Java", java, ["-version"]);
    if (version && !/version "17\./.test(version)) failures.push("当前 Java 不是 JDK 17");
    else if (version) notes.push(version.split("\n")[0]);
  }
}

if (config.ANDROID_SDK_ROOT) {
  for (const relativePath of ["platform-tools/adb", "platform-tools", "platforms"]) {
    if (!existsSync(join(config.ANDROID_SDK_ROOT, relativePath))) {
      failures.push(`Android SDK 缺少 ${relativePath}`);
    }
  }
}

if (config.PRIMARY_ADB) {
  if (!existsSync(config.PRIMARY_ADB)) failures.push("PRIMARY_ADB 不存在");
  else {
    const version = run("ADB", config.PRIMARY_ADB, ["version"]);
    if (version) notes.push(version.split("\n")[0]);
  }
}

if (process.argv.includes("--device")) {
  const serial = config.INSTALLER_TEST_ADB_SERIAL;
  if (!serial) failures.push("设备检查需要 INSTALLER_TEST_ADB_SERIAL");
  else if (config.PRIMARY_ADB && existsSync(config.PRIMARY_ADB)) {
    const devices = run("ADB 设备列表", config.PRIMARY_ADB, ["devices"]);
    const line = devices.split(/\r?\n/).find((item) => item.startsWith(`${serial}\t`));
    if (!line) failures.push("ADB 设备列表中未发现显式目标设备");
    else if (!line.endsWith("\tdevice")) failures.push(`目标设备状态异常：${line}`);
    else notes.push(`目标设备已连接：${serial}`);
  }
}

if (failures.length > 0) {
  console.error("本机环境检查失败：");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("本机环境检查通过。");
for (const note of notes) console.log(`- ${note}`);

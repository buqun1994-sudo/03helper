#!/usr/bin/env node

import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(fileURLToPath(new URL("..", import.meta.url)));
const versionFile = join(root, "release-version.properties");
const versionPattern = /^(\d+)\.(\d+)\.(\d+)$/;

function fail(message) {
    console.error(`Release version check failed: ${message}`);
    process.exit(1);
}

function parseProperties(content) {
    const values = new Map();
    for (const rawLine of content.split(/\r?\n/)) {
        const line = rawLine.trim();
        if (!line || line.startsWith("#")) continue;
        const separator = line.indexOf("=");
        if (separator < 1) fail(`invalid property line: ${rawLine}`);
        values.set(line.slice(0, separator).trim(), line.slice(separator + 1).trim());
    }
    return values;
}

function readVersion() {
    const values = parseProperties(readFileSync(versionFile, "utf8"));
    const name = values.get("releaseVersionName");
    const code = Number(values.get("releaseVersionCode"));
    if (!name || !versionPattern.test(name)) {
        fail("releaseVersionName must match <major>.<minor>.<patch>");
    }
    if (!Number.isSafeInteger(code) || code < 1) {
        fail("releaseVersionCode must be a positive integer");
    }
    return { name, code };
}

const args = process.argv.slice(2);
const checkOnly = args.includes("--check");
const dryRun = args.includes("--dry-run");
const versionFlag = args.indexOf("--version");
const explicitVersion = versionFlag >= 0 ? args[versionFlag + 1] : undefined;
if (versionFlag >= 0 && (!explicitVersion || explicitVersion.startsWith("--"))) {
    fail("--version requires a value");
}
if (args.some((arg) => !["--check", "--dry-run", "--version", explicitVersion].includes(arg))) {
    fail("usage: bump-release-version.mjs [--check] [--dry-run] [--version <major.minor.patch>]");
}

const current = readVersion();
if (checkOnly) {
    console.log(`${current.name} (versionCode ${current.code})`);
    process.exit(0);
}

const match = (explicitVersion || current.name).match(versionPattern);
if (explicitVersion && !match) {
    fail("explicit version must match <major>.<minor>.<patch>");
}
const nextName = explicitVersion || `${match[1]}.${match[2]}.${Number(match[3]) + 1}`;
const next = `releaseVersionName=${nextName}\nreleaseVersionCode=${current.code + 1}\n`;
if (!dryRun) writeFileSync(versionFile, next, "utf8");
console.log(`${dryRun ? "next" : "updated"}: ${nextName} (versionCode ${current.code + 1})`);

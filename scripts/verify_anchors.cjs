#!/usr/bin/env node
/**
 * Anchor audit for Review Atlas HTML files.
 *
 * Usage: node verify_anchors.cjs docs/review-atlas.html --root .
 *        (first argument: atlas HTML path; --root: target project root, default cwd)
 *
 * Parses prefix constants, the F{} file map and every L(F.key, "Class", "method", line[, "sig"])
 * call in the atlas, then verifies:
 *   1. the referenced file exists under the given project root
 *   2. the method token appears within ±3 lines of the claimed line
 * Mismatches with a non-empty sig are reported as WARN (intentional call-site anchors);
 * mismatches without sig and missing files are hard issues (exit code 1).
 */
"use strict";
const fs = require("fs");
const path = require("path");

const args = process.argv.slice(2);
const htmlPath = args[0];
let root = process.cwd();
const rootIdx = args.indexOf("--root");
if (rootIdx > -1) root = args[rootIdx + 1];
if (!htmlPath) {
  console.log("usage: node verify_anchors.cjs docs/review-atlas.html --root .");
  process.exit(2);
}

const html = fs.readFileSync(htmlPath, "utf8");

// prefix constants: var SRC = "src/";
const prefixes = {};
for (const m of html.matchAll(/var\s+([A-Za-z_$][\w$]*)\s*=\s*"([^"\\]*)"\s*;/g)) prefixes[m[1]] = m[2];

// F map block
const files = {};
const fBlock = html.match(/var\s+F\s*=\s*\{([\s\S]*?)\n\s*\};/);
if (fBlock) {
  const entryRe = /([A-Za-z_$][\w$]*)\s*:\s*(?:([A-Za-z_$][\w$]*)\s*\+\s*)?"([^"\\]*)"\s*,?/g;
  for (const m of fBlock[1].matchAll(entryRe)) {
    const base = m[2] ? (prefixes[m[2]] || "") : "";
    files[m[1]] = base + m[3];
  }
}

// L(F.key, "Class", "method", line[, "sig"])
const callRe = /L\(\s*F\.([A-Za-z_$][\w$]*)\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*(\d+)\s*(?:,\s*"((?:[^"\\]|\\.)*)")?/g;
const anchors = [];
for (const m of html.matchAll(callRe)) {
  anchors.push({ key: m[1], cls: m[2], method: m[3], line: +m[4], sig: m[5] || "" });
}

if (anchors.length === 0) {
  console.log("no L(F.*) anchors found — is this an atlas file?");
  process.exit(2);
}

const issues = [];
const warns = [];
let ok = 0;
const cache = new Map();

for (const a of anchors) {
  const rel = files[a.key];
  if (!rel) {
    issues.push(`unknown F key '${a.key}' (${a.method}:${a.line})`);
    continue;
  }
  const abs = path.resolve(root, rel);
  if (!fs.existsSync(abs)) {
    issues.push(`missing file ${rel} (${a.cls}.${a.method}:${a.line})`);
    continue;
  }
  if (!/^[A-Za-z_$][\w$]*$/.test(a.method)) { ok++; continue; } // label-style methods (e.g. 枚举定义)
  const lines = cache.get(abs) || fs.readFileSync(abs, "utf8").split(/\r?\n/);
  cache.set(abs, lines);
  const near = lines.slice(Math.max(0, a.line - 4), a.line + 3).join("\n");
  if (near.includes(a.method)) {
    ok++;
  } else if (a.sig) {
    warns.push(`callsite anchor ${rel.split("/").pop()}:${a.line} ${a.cls}.${a.method} (sig: ${a.sig})`);
  } else {
    issues.push(`line mismatch ${rel}:${a.line} ${a.cls}.${a.method} — method not within ±3 lines`);
  }
}

const summary = {
  anchors: anchors.length,
  files: Object.keys(files).length,
  ok,
  callsiteWarns: warns.length,
  issues: issues.length
};
console.log(JSON.stringify(summary));
for (const w of warns.slice(0, 20)) console.log("WARN " + w);
if (warns.length > 20) console.log(`… ${warns.length - 20} more warns`);
for (const i of issues.slice(0, 40)) console.log("ISSUE " + i);
if (issues.length > 40) console.log(`… ${issues.length - 40} more issues`);
process.exit(issues.length > 0 ? 1 : 0);

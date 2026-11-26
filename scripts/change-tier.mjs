#!/usr/bin/env node
/**
 * 变更识别（Java+MySQL 栈）：分析 git diff，输出变更档位（fast/full）与类型。
 * 设计原则：正向白名单才进 fast，其余一律 full；识别失败回退 full。
 * 输出 GITHUB_OUTPUT: tier=fast|full, types=ui,business,...
 */
import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";

function git(args) {
  return execFileSync("git", args, { encoding: "utf-8" }).trim();
}

function changedFiles() {
  const base = process.env.BASE_SHA;
  let out = "";
  try {
    if (base) {
      out = git(["diff", "--name-status", base, "HEAD"]);
    } else {
      // 无 base：比较上一次提交
      out = git(["diff", "--name-status", "HEAD~1", "HEAD"]);
    }
  } catch {
    // 首次提交等无父提交场景：视为全量
    return null;
  }
  const files = [];
  for (const line of out.split("\n")) {
    if (!line.trim()) continue;
    const [status, ...rest] = line.split("\t");
    files.push({ status: status[0], path: rest.join("\t") });
  }
  return files;
}

// 高风险路径：命中即 full
const HIGH_RISK = [
  /^deploy\//, /^\.github\//, /^docker-compose/, /^nginx\.conf/,
  /mysql-init\//, /\.sql$/, /^scripts\//,
  /SysMenuServiceImpl\.java$/, /\/auth\//, /\/security\//,
  /\/controller\//, /\/api\//, /\/mapper\//, /\/worker\//, /\/scheduled\//,
  /pom\.xml$/, /package(-lock)?\.json$/,
];
// 高风险语义：文件名/路径含这些词即 full
const HIGH_RISK_WORD = /(auth|permission|rbac|tenant|scope|security|encrypt|credential|session|audit|isolation|migration|schema)/i;
// 安全白名单：只有这些路径允许进 fast
const SAFE = [
  /^wimoor\/wimoor-ui\/src\/views\/.*\.vue$/,
  /^wimoor\/wimoor-ui\/src\/.*\.(css|scss|svg)$/,
  /^docs\//, /^outputs\//,
];
const MAX_FAST_FILES = 12;

function classify(files) {
  if (files === null) return { tier: "full", types: ["unknown"], reason: "无基线，按全量处理" };
  if (files.length === 0) return { tier: "full", types: ["none"], reason: "未识别到变更文件" };

  const types = new Set();
  let highRisk = false;

  for (const { status, path } of files) {
    // 新增/删除/重命名 -> full（防御夹带）
    if (status === "A" || status === "D" || status === "R") highRisk = true;

    if (/\.sql$|mysql-init\//.test(path)) { types.add("database"); highRisk = true; }
    else if (/SysMenuServiceImpl|permission|auth|security|tenant|scope/i.test(path)) { types.add("permission"); highRisk = true; }
    else if (/\/worker\/|\/scheduled\//.test(path)) { types.add("worker"); highRisk = true; }
    else if (/\/controller\/|\/api\//.test(path)) { types.add("api"); highRisk = true; }
    else if (/pom\.xml$|package(-lock)?\.json$|^\.github\/|^deploy\//.test(path)) { types.add("infra"); highRisk = true; }
    else if (/\.vue$|\.css$|\.scss$/.test(path)) { types.add("ui"); }
    else { types.add("business"); }

    if (HIGH_RISK.some((re) => re.test(path)) || HIGH_RISK_WORD.test(path)) highRisk = true;
    // 白名单外的改动一律 full
    if (!SAFE.some((re) => re.test(path))) highRisk = true;
  }

  const tier = (!highRisk && files.length <= MAX_FAST_FILES) ? "fast" : "full";
  const reason = tier === "fast"
    ? `全部落在 UI/文档白名单（${files.length} 个文件）`
    : `命中高风险路径/语义或非白名单文件（${files.length} 个文件）`;
  return { tier, types: [...types], reason };
}

try {
  const files = changedFiles();
  const result = classify(files);
  const report = {
    tier: result.tier,
    types: result.types,
    reason: result.reason,
    changedCount: files ? files.length : -1,
  };
  if (process.env.GITHUB_OUTPUT) {
    const lines = [
      `tier=${result.tier}`,
      `types=${result.types.join(",")}`,
      `reason=${result.reason.replace(/[\r\n]/g, " ")}`,
    ];
    writeFileSync(process.env.GITHUB_OUTPUT, `${lines.join("\n")}\n`, { flag: "a" });
  }
  console.log(JSON.stringify(report, null, 2));
} catch (err) {
  // 任何异常都回退 full（安全兜底）
  console.error("变更识别异常，回退 full:", err.message);
  if (process.env.GITHUB_OUTPUT) {
    writeFileSync(process.env.GITHUB_OUTPUT, "tier=full\ntypes=unknown\nreason=识别异常回退full\n", { flag: "a" });
  }
  process.exit(0); // 不阻断构建，只是保守地按 full 处理
}

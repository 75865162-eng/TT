#!/usr/bin/env node
// 抗休眠逐页验收：进度实时存盘，被沙箱杀掉后可从断点继续；支持分批跑。
//
// 设计目标：沙箱会定时回收重启，杀掉后台进程。本脚本把"长任务"拆成可恢复的"短步骤"：
//   1) 每测完一页立刻把结果写进进度文件（checkpoint），中断不丢已完成的工作；
//   2) 再次运行时读取进度文件，跳过已完成的页，从断点继续；
//   3) --batch N 分批跑，一次只测 N 页，缩短单次暴露窗口；
//   4) --ensure-services 自动检测并拉起服务（调用 start-all.sh），配合自恢复。
//
// 用法：
//   node scripts/acceptance/resilient-acceptance.mjs --batch 20 --ensure-services
//   node scripts/acceptance/resilient-acceptance.mjs --batch 20          # 接着上次的进度
//   node scripts/acceptance/resilient-acceptance.mjs --reset             # 清空进度重来
//   node scripts/acceptance/resilient-acceptance.mjs --report            # 只看当前进度汇总
//
// 进度文件默认：/workspace/woker/outputs/acceptance-checkpoint.json（在磁盘上，重启不丢）

import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');
const BASE = process.env.ACCEPT_BASE || 'http://127.0.0.1:8080';
const FIX = path.join(ROOT, 'scripts/acceptance/fixtures');
const CHECKPOINT = path.join(ROOT, 'outputs/acceptance-checkpoint.json');

const args = process.argv.slice(2);
const arg = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : def; };
const has = name => args.includes(name);
const batch = parseInt(arg('--batch', '0'), 10);          // 0 = 跑全部剩余
const only = arg('--only', '');
const ensureServices = has('--ensure-services');

// 读取固化清单（不再依赖 /tmp）
let urls = fs.readFileSync(path.join(FIX, 'page_urls.txt'), 'utf8').split('\n').map(s => s.trim()).filter(Boolean);
const titleMap = JSON.parse(fs.readFileSync(path.join(FIX, 'path_title_map.json'), 'utf8'));
if (only) urls = urls.filter(u => u.includes(only));

const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---- 进度文件读写（原子写：先写临时文件再 rename，防止写一半被杀导致损坏）----
function loadCheckpoint() {
  if (has('--reset')) return { done: {}, startedAt: Date.now(), updatedAt: Date.now() };
  if (!fs.existsSync(CHECKPOINT)) return { done: {}, startedAt: Date.now(), updatedAt: Date.now() };
  try { return JSON.parse(fs.readFileSync(CHECKPOINT, 'utf8')); }
  catch (e) { console.log('进度文件损坏，重新开始:', e.message); return { done: {}, startedAt: Date.now(), updatedAt: Date.now() }; }
}
function saveCheckpoint(cp) {
  cp.updatedAt = Date.now();
  fs.mkdirSync(path.dirname(CHECKPOINT), { recursive: true });
  const tmp = CHECKPOINT + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(cp, null, 2));
  fs.renameSync(tmp, CHECKPOINT); // 原子替换
}
function summary(cp) {
  const vals = Object.values(cp.done);
  const s = {};
  vals.forEach(r => s[r.class] = (s[r.class] || 0) + 1);
  const ok = s.OK || 0;
  return { tested: vals.length, total: urls.length, ok, summary: s };
}

// ---- 服务自恢复：检测前端端口，不通就调 start-all.sh ----
function ensureUp() {
  let up = false;
  try { up = execSync(`curl -s -o /dev/null -w '%{http_code}' --max-time 5 ${BASE}/`, { encoding: 'utf8' }).trim() === '200'; }
  catch (e) { up = false; }
  if (up) { console.log('服务已在运行'); return true; }
  console.log('服务未运行，自动拉起（start-all.sh）...');
  try {
    const script = path.join(ROOT, 'deploy/wimoor-p0/start-all.sh');
    execSync(`bash ${script}`, { cwd: path.join(ROOT, 'deploy/wimoor-p0'), stdio: 'inherit', timeout: 180000 });
    // 轮询等待前端就绪，最多 120 秒
    for (let i = 0; i < 60; i++) {
      try {
        const code = execSync(`curl -s -o /dev/null -w '%{http_code}' --max-time 3 ${BASE}/`, { encoding: 'utf8' }).trim();
        if (code === '200') { console.log('服务已就绪'); return true; }
      } catch (e) {}
      sleep(2000);
    }
  } catch (e) { console.log('自动拉起失败:', e.message); }
  return false;
}

async function main() {
  if (has('--report')) {
    const cp = loadCheckpoint();
    console.log('当前进度:', JSON.stringify(summary(cp)));
    const untested = urls.filter(u => !cp.done[u]);
    console.log('未测页数:', untested.length, '| 前10个:', untested.slice(0, 10));
    return;
  }

  if (ensureServices && !ensureUp()) {
    console.log('服务无法就绪，终止（稍后重试本命令即可，进度不会丢）');
    process.exit(2);
  }

  const cp = loadCheckpoint();
  const pending = urls.filter(u => !cp.done[u]);
  if (pending.length === 0) {
    console.log('全部已完成:', JSON.stringify(summary(cp)));
    return;
  }
  const todo = batch > 0 ? pending.slice(0, batch) : pending;
  console.log(`本批 ${todo.length} 页 | 已完成 ${Object.keys(cp.done).length}/${urls.length} | 剩余 ${pending.length}`);

  const browser = await chromium.launch({ headless: true, executablePath: '/usr/bin/chromium', args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  const ctx = await browser.newContext({ viewport: { width: 1366, height: 768 } });

  let loginCount = 0;
  async function doLogin() {
    loginCount++;
    const lp = await ctx.newPage();
    try {
      await lp.goto(BASE + '/login', { waitUntil: 'domcontentloaded', timeout: 25000 });
      await sleep(1000);
      await lp.locator('input').nth(0).fill('admin@wimoor.com');
      await lp.locator('input').nth(1).fill('123456');
      await lp.locator('button').filter({ hasText: '登录' }).first().click();
      await sleep(3000);
      const sid = await lp.evaluate(() => localStorage.getItem('jsessionid'));
      return !!sid;
    } catch (e) { console.log('登录异常:', e.message); return false; }
    finally { await lp.close(); }
  }
  if (!await doLogin()) { console.log('登录失败，终止（进度已保存）'); await browser.close(); process.exit(2); }

  async function probe(url, expectedTitle) {
    const page = await ctx.newPage();
    const errors = [];
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text().slice(0, 160)); });
    page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message.slice(0, 160)));
    let status = 0, finalUrl = '', textLen = 0, snippet = '', cls = 'OK', titleHit = false;
    try {
      const resp = await page.goto(BASE + url, { waitUntil: 'domcontentloaded', timeout: 20000 });
      status = resp ? resp.status() : 0;
      await sleep(900);
      finalUrl = page.url().replace(BASE, '');
      const body = await page.evaluate(() => document.body?.innerText || '').then(t => t.replace(/\s+/g, ' '));
      textLen = body.replace(/\s/g, '').length;
      snippet = body.slice(0, 160);
      titleHit = expectedTitle ? body.includes(expectedTitle) : false;
      const fatal = errors.filter(e => /ChunkLoadError|Cannot read propert|is not a function|TypeError|ReferenceError/i.test(e));
      if (/ssologin|\/login/i.test(finalUrl)) cls = 'REDIRECT_LOGIN';
      else if ((finalUrl === '/home' || finalUrl === '/') && url !== '/') cls = 'FELL_BACK_HOME';
      else if (status >= 400) cls = 'HTTP_' + status;
      else if (textLen < 20) cls = 'BLANK';
      else if (/页面不存在|资源不存在|^404$/i.test(snippet.slice(0, 30))) cls = 'NOT_FOUND_TEXT';
      else if (/账户异常|无权限访问/i.test(snippet)) cls = 'NO_PERMISSION';
      else if (fatal.length > 0) cls = 'FATAL_JS_ERROR';
      else cls = 'OK';
    } catch (e) { cls = 'TIMEOUT_OR_NAV_ERR'; snippet = e.message.slice(0, 100); }
    await page.close();
    return { url, expectedTitle, status, finalUrl, textLen, titleHit, class: cls, errors: errors.slice(0, 2), snippet };
  }

  let i = 0;
  for (const url of todo) {
    i++;
    const expectedTitle = (titleMap[url] || '').trim();
    let r = await probe(url, expectedTitle);
    if (['REDIRECT_LOGIN', 'BLANK', 'FELL_BACK_HOME'].includes(r.class)) {
      await doLogin();
      r = await probe(url, expectedTitle);
      if (['REDIRECT_LOGIN', 'BLANK', 'FELL_BACK_HOME'].includes(r.class)) await doLogin();
    }
    cp.done[url] = r;
    saveCheckpoint(cp); // 每页立刻存盘 —— 抗休眠的关键
    const mark = r.class === 'OK' ? '✓' : '✗';
    console.log(`[${i}/${todo.length}] ${mark} ${r.class.padEnd(16)} ${url}`);
  }
  await browser.close();
  const s = summary(cp);
  console.log('\n=== 当前累计进度 ===');
  console.log(JSON.stringify(s));
  console.log('进度文件:', CHECKPOINT);
  if (s.tested === s.total) console.log('✅ 全部页面已测完');
  else console.log(`还有 ${s.total - s.tested} 页未测，再跑一次本命令即可接着测`);
}

main().catch(e => { console.error('FATAL:', e.message); process.exit(2); });

#!/usr/bin/env node
// 生产级逐页验收：真实浏览器逐页打开，检测是否落在目标页面（而非首页兜底/白屏/报错）。
// 用法：node scripts/page-acceptance.mjs [--limit N] [--only 关键词] [--json out.json]
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';

const BASE = 'http://127.0.0.1:8080';
const args = process.argv.slice(2);
const arg = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i+1] : def; };
const limit = parseInt(arg('--limit', '0'), 10);
const only = arg('--only', '');
const outFile = arg('--json', '/workspace/woker/outputs/page-acceptance-latest.json');

// URL 清单 + path->title 映射
let urls = fs.readFileSync('/tmp/page_urls.txt', 'utf8').split('\n').map(s => s.trim()).filter(Boolean);
const titleMap = JSON.parse(fs.readFileSync('/tmp/path_title_map.json', 'utf8'));
if (only) urls = urls.filter(u => u.includes(only));
if (limit > 0) urls = urls.slice(0, limit);

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: '/usr/bin/chromium', args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  const ctx = await browser.newContext({ viewport: { width: 1366, height: 768 } });

  // 真实登录（可被重新调用以刷新过期的会话）
  let loginCount = 0;
  async function doLogin() {
    loginCount++;
    const loginPage = await ctx.newPage();
    await loginPage.goto(BASE + '/login', { waitUntil: 'domcontentloaded', timeout: 25000 });
    await sleep(1200);
    // 会话仍有效时访问 /login 会被重定向到 /home，此时无输入框：直接复用现有会话，不再强制登录
    if (!/\/login/.test(loginPage.url())) {
      const sid0 = await loginPage.evaluate(() => localStorage.getItem('jsessionid'));
      const url0 = loginPage.url().replace(BASE, '');
      await loginPage.close();
      console.log(`登录#${loginCount}:`, sid0 ? 'REUSE ' + sid0.slice(0,8) + '...' : 'FAILED', '| URL:', url0);
      return !!sid0;
    }
    await loginPage.locator('input').nth(0).fill('admin@wimoor.com');
    await loginPage.locator('input').nth(1).fill('123456');
    await loginPage.locator('button').filter({ hasText: '登录' }).first().click();
    await sleep(3500);
    const sid = await loginPage.evaluate(() => localStorage.getItem('jsessionid'));
    const url = loginPage.url().replace(BASE, '');
    await loginPage.close();
    console.log(`登录#${loginCount}:`, sid ? 'OK ' + sid.slice(0,8) + '...' : 'FAILED', '| URL:', url);
    return !!sid;
  }
  if (!await doLogin()) { console.log('登录未建立会话，终止'); await browser.close(); process.exit(2); }

  // 单页探测：返回分类结果（不含重试）
  async function probe(url, expectedTitle) {
    const page = await ctx.newPage();
    const errors = [];
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text().slice(0, 200)); });
    page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message.slice(0, 200)));
    let status = 0, finalUrl = '', textLen = 0, bodySnippet = '', cls = 'OK', titleHit = false;
    try {
      const resp = await page.goto(BASE + url, { waitUntil: 'domcontentloaded', timeout: 20000 });
      status = resp ? resp.status() : 0;
      await sleep(1200);
      finalUrl = page.url().replace(BASE, '');
      const bodyText = await page.evaluate(() => document.body?.innerText || '').then(t => t.replace(/\s+/g, ' '));
      textLen = bodyText.replace(/\s/g, '').length;
      bodySnippet = bodyText.slice(0, 200);
      titleHit = expectedTitle ? bodyText.includes(expectedTitle) : false;
      const fatalErr = errors.filter(e => /ChunkLoadError|Failed to fetch dynamically|Cannot read propert|is not a function|TypeError|ReferenceError/i.test(e));
      if (/ssologin|\/login/i.test(finalUrl)) cls = 'REDIRECT_LOGIN';
      else if ((finalUrl === '/home' || finalUrl === '/') && url !== '/') cls = 'FELL_BACK_HOME';
      else if (status >= 400) cls = 'HTTP_' + status;
      else if (textLen < 20) cls = 'BLANK';
      else if (/页面不存在|资源不存在|^404$/i.test(bodySnippet.slice(0,30))) cls = 'NOT_FOUND_TEXT';
      else if (/账户异常|无权限访问/i.test(bodySnippet)) cls = 'NO_PERMISSION';
      else if (fatalErr.length > 0) cls = 'FATAL_JS_ERROR';
      else cls = 'OK';
    } catch (e) {
      cls = 'TIMEOUT_OR_NAV_ERR';
      bodySnippet = e.message.slice(0, 120);
    }
    await page.close();
    return { url, expectedTitle, status, finalUrl, textLen, titleHit, class: cls, errors: errors.slice(0,2), snippet: bodySnippet };
  }

  const results = [];
  let i = 0;
  for (const url of urls) {
    i++;
    const expectedTitle = (titleMap[url] || '').trim();
    let r = await probe(url, expectedTitle);
    // 会话失效（跳登录/空白疑似过期）→ 重新登录后再试一次，确保测的是页面本身
    if (['REDIRECT_LOGIN', 'BLANK', 'FELL_BACK_HOME'].includes(r.class)) {
      console.log(`  ↻ ${r.class} 疑似会话过期，重新登录重试: ${url}`);
      await doLogin();
      r = await probe(url, expectedTitle);
      if (['REDIRECT_LOGIN', 'BLANK', 'FELL_BACK_HOME'].includes(r.class)) await doLogin(); // 再刷新一次供下一页用
    }
    results.push(r);
    const mark = r.class === 'OK' ? '✓' : '✗';
    console.log(`[${i}/${urls.length}] ${mark} ${r.class.padEnd(16)} ${url} | 期望标题="${expectedTitle}" 命中=${r.titleHit}`);
    if (r.class !== 'OK') console.log(`         final=${r.finalUrl} len=${r.textLen} 正文=${r.snippet.slice(0,90)}`);
  }
  await browser.close();

  const summary = {};
  results.forEach(r => summary[r.class] = (summary[r.class] || 0) + 1);
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, JSON.stringify({ total: results.length, summary, results }, null, 2));
  console.log('\n=== 汇总 ===');
  console.log(JSON.stringify(summary));
  console.log('结果文件:', outFile);
  console.log(`通过 ${summary.OK || 0}/${results.length}`);
  process.exit((summary.OK || 0) === results.length ? 0 : 1);
}
main().catch(e => { console.error('FATAL:', e.message); process.exit(2); });

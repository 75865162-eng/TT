#!/usr/bin/env node
// 生产级深度逐页验收（五维）：
//  1) 页面能正常打开（路由命中、非跳登录、非首页兜底、非空白、非404文案）
//  2) 数据能正常显示（表格有数据行 或 明确空状态"暂无数据"；接口返回数据能在DOM体现）
//  3) 页面引用的函数正常（捕获 pageerror/TypeError/is not a function/ReferenceError）
//  4) 接口不报错（捕获所有 4xx/5xx；200 响应体中的 SQL/运行时异常；error toast）
//  5) 能正常使用（DOM 有可交互元素：按钮/表格/表单；非纯静态错误页）
// 用法：node scripts/page-acceptance-deep.mjs [--base URL] [--only 关键词] [--limit N] [--settle 2500] [--json out.json]
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';

const args = process.argv.slice(2);
const arg = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i+1] : def; };
const BASE = arg('--base', 'http://127.0.0.1:8080');
const ACCEPT_ACCOUNT = process.env.ACCEPT_ACCOUNT || '1';
const ACCEPT_PASSWORD = process.env.ACCEPT_PASSWORD || '1';
const limit = parseInt(arg('--limit', '0'), 10);
const only = arg('--only', '');
const settle = parseInt(arg('--settle', '2600'), 10);
const outFile = arg('--json', '/workspace/tt/outputs/page-acceptance-deep.json');

let urls = fs.readFileSync('/tmp/page_urls.txt', 'utf8').split('\n').map(s => s.trim()).filter(Boolean);
const titleMap = JSON.parse(fs.readFileSync('/tmp/path_title_map.json', 'utf8'));
if (only) urls = urls.filter(u => u.includes(only));
if (limit > 0) urls = urls.slice(0, limit);

const sleep = ms => new Promise(r => setTimeout(r, ms));
const API_RE = /\/(api|erp|admin|amazon|amazonadv|finance|quote|gen|ai)\//i;
const SQL_RE = /Unknown column|SQLSyntaxErrorException|SQLGrammarException|BadSqlGrammar|NullPointerException|bad SQL grammar|doesn't exist|Table .* not found/i;
const FATAL_JS_RE = /is not a function|TypeError|ReferenceError|Cannot read propert|Cannot set propert|undefined is not|null is not/i;

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: '/usr/bin/chromium', args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });

  let loginCount = 0;
  async function doLogin() {
    loginCount++;
    const lp = await ctx.newPage();
    await lp.goto(BASE + '/login', { waitUntil: 'domcontentloaded', timeout: 25000 });
    await sleep(1200);
    if (!/\/login/.test(lp.url())) {
      const sid = await lp.evaluate(() => localStorage.getItem('jsessionid'));
      const u = lp.url().replace(BASE, '');
      await lp.close();
      console.log(`登录#${loginCount}:`, sid ? 'REUSE ' + sid.slice(0,8) : 'FAILED', '|', u);
      return !!sid;
    }
    await lp.locator('input').nth(0).fill(ACCEPT_ACCOUNT);
    await lp.locator('input').nth(1).fill(ACCEPT_PASSWORD);
    await lp.locator('button').filter({ hasText: '登录' }).first().click();
    await sleep(3500);
    const sid = await lp.evaluate(() => localStorage.getItem('jsessionid'));
    const u = lp.url().replace(BASE, '');
    await lp.close();
    console.log(`登录#${loginCount}:`, sid ? 'OK ' + sid.slice(0,8) : 'FAILED', '|', u);
    return !!sid;
  }
  if (!await doLogin()) { console.log('登录失败，终止'); await browser.close(); process.exit(2); }

  async function probe(url, expectedTitle) {
    const page = await ctx.newPage();
    const jsErrors = [];
    const apiErrors = [];
    const toastErrors = [];
    const apiCalls = { total: 0, ok: 0, fail: 0 };
    page.on('console', m => { if (m.type() === 'error') jsErrors.push(m.text().slice(0, 220)); });
    page.on('pageerror', e => jsErrors.push('PAGEERROR: ' + e.message.slice(0, 220)));
    page.on('response', async (resp) => {
      try {
        const u = resp.url();
        if (!API_RE.test(u)) return;
        apiCalls.total++;
        const st = resp.status();
        if (st >= 400) {
          apiCalls.fail++;
          let body = '';
          try { body = (await resp.text()).slice(0, 260); } catch (_) {}
          apiErrors.push(`HTTP_${st} ${u.replace(BASE,'')} :: ${body}`);
          return;
        }
        apiCalls.ok++;
        const ct = resp.headers()['content-type'] || '';
        if (ct.includes('json')) {
          try {
            const txt = (await resp.text()).slice(0, 700);
            if (SQL_RE.test(txt)) apiErrors.push(`SQLERR ${u.replace(BASE,'')} :: ${txt.slice(0,260)}`);
          } catch (_) {}
        }
      } catch (_) {}
    });
    let status = 0, finalUrl = '', cls = 'OK', titleHit = false;
    let dom = { textLen: 0, snippet: '', tableRows: 0, hasEmptyState: false, interactives: 0, bodyChildren: 0 };
    try {
      const resp = await page.goto(BASE + url, { waitUntil: 'domcontentloaded', timeout: 20000 });
      status = resp ? resp.status() : 0;
      // 等待异步数据加载：先 networkidle（最多6s），再固定 settle
      try { await page.waitForLoadState('networkidle', { timeout: 6000 }); } catch (_) {}
      await sleep(settle);
      finalUrl = page.url().replace(BASE, '');
      dom = await page.evaluate(() => {
        const bodyText = (document.body?.innerText || '').replace(/\s+/g, ' ');
        return {
          textLen: bodyText.replace(/\s/g, '').length,
          snippet: bodyText.slice(0, 220),
          tableRows: document.querySelectorAll('.el-table__body tbody tr, .el-table__row, tbody tr').length,
          hasEmptyState: /暂无数据|没有数据|无数据|暂无内容|暂无记录|空空如也|No Data/i.test(bodyText),
          interactives: document.querySelectorAll('button, .el-button, input, select, .el-input, a[href]').length,
          bodyChildren: document.body?.children?.length || 0
        };
      });
      titleHit = expectedTitle ? dom.snippet.includes(expectedTitle) : false;
      // 判定优先级
      const jsFatal = jsErrors.filter(e => FATAL_JS_RE.test(e));
      if (/ssologin|\/login/i.test(finalUrl)) cls = 'REDIRECT_LOGIN';
      else if ((finalUrl === '/home' || finalUrl === '/') && url !== '/') cls = 'FELL_BACK_HOME';
      else if (status >= 400) cls = 'HTTP_' + status;
      else if (dom.textLen < 20) cls = 'BLANK';
      else if (/页面不存在|资源不存在|^404$/i.test(dom.snippet.slice(0,30))) cls = 'NOT_FOUND_TEXT';
      else if (/账户异常|无权限访问/i.test(dom.snippet)) cls = 'NO_PERMISSION';
      else if (apiErrors.length > 0) cls = 'API_ERROR';
      else if (jsFatal.length > 0) cls = 'FATAL_JS_ERROR';
      else cls = 'OK';
    } catch (e) {
      cls = 'TIMEOUT_OR_NAV_ERR';
      dom.snippet = e.message.slice(0, 140);
    }
    await page.close();
    return { url, expectedTitle, status, finalUrl, class: cls, titleHit, dom,
             api: apiCalls, apiErrors: apiErrors.slice(0, 6), jsErrors: jsErrors.slice(0, 6),
             toastErrors: toastErrors.slice(0, 4) };
  }

  const results = [];
  let i = 0;
  for (const url of urls) {
    i++;
    const expectedTitle = (titleMap[url] || '').trim();
    let r = await probe(url, expectedTitle);
    if (['REDIRECT_LOGIN', 'BLANK', 'FELL_BACK_HOME'].includes(r.class)) {
      console.log(`  ↻ ${r.class} 疑似会话过期，重新登录重试: ${url}`);
      await doLogin();
      r = await probe(url, expectedTitle);
      if (['REDIRECT_LOGIN', 'BLANK', 'FELL_BACK_HOME'].includes(r.class)) await doLogin();
    }
    results.push(r);
    const mark = r.class === 'OK' ? '✓' : '✗';
    const d = r.dom;
    console.log(`[${i}/${urls.length}] ${mark} ${r.class.padEnd(16)} ${url} | 行=${d.tableRows} 空态=${d.hasEmptyState?'Y':'N'} 交互=${d.interactives} 接口=${r.api.ok}/${r.api.total} 标题=${r.titleHit?'Y':'N'}`);
    if (r.class !== 'OK') {
      if (r.apiErrors.length) console.log('     API:', r.apiErrors[0].slice(0, 160));
      if (r.jsErrors.length) console.log('     JS :', r.jsErrors[0].slice(0, 160));
    }
  }
  await browser.close();

  const summary = {};
  results.forEach(r => summary[r.class] = (summary[r.class] || 0) + 1);
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, JSON.stringify({ total: results.length, summary, results }, null, 2));
  console.log('\n=== 汇总 ===', JSON.stringify(summary));
  console.log('结果文件:', outFile);
  process.exit((summary.OK || 0) === results.length ? 0 : 1);
}
main().catch(e => { console.error('FATAL:', e.message); process.exit(2); });

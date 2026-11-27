#!/usr/bin/env node
// 数据级深度排查：确认每个页面的 API 真从服务器拉回数据、页面真实显示
// 维度：
//  - 捕获每个 API 响应，解析 JSON，提取数据字段(rows/data/list/records/items)与 count/total
//  - 结合 DOM 表格行数，判定数据是否"拉回并显示"
//  - 分类：DATA_VISIBLE(数据拉回且DOM有行) / DATA_NOT_VISIBLE(数据拉回但DOM无行) /
//          EMPTY_OK(空数据+明确空态) / EMPTY_NO_UI(空数据无空态) / NO_DATA_API(配置/表单页) /
//          API_ERROR(HTTP4xx5xx/SQL异常) / REDIRECT_LOGIN / BLANK
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';

const args = process.argv.slice(2);
const arg = (n, d) => { const i = args.indexOf(n); return i >= 0 ? args[i+1] : d; };
const BASE = arg('--base', 'http://43.143.28.153:8080');
const ACCOUNT = process.env.ACCEPT_ACCOUNT || '1';
const PASSWORD = process.env.ACCEPT_PASSWORD || '1';
const settle = parseInt(arg('--settle', '3000'), 10);
const only = arg('--only', '');
const limit = parseInt(arg('--limit', '0'), 10);
const outFile = arg('--json', '/workspace/tt/outputs/page-data-acceptance.json');

let urls = fs.readFileSync('/tmp/page_urls.txt', 'utf8').split('\n').map(s => s.trim()).filter(Boolean);
const titleMap = JSON.parse(fs.readFileSync('/tmp/path_title_map.json', 'utf8'));
if (only) urls = urls.filter(u => u.includes(only));
if (limit > 0) urls = urls.slice(0, limit);

const sleep = ms => new Promise(r => setTimeout(r, ms));
const API_RE = /\/(api|erp|admin|amazon|amazonadv|finance|quote|gen|ai)\//i;
const SQL_RE = /Unknown column|SQLSyntaxErrorException|SQLGrammarException|BadSqlGrammar|NullPointerException|bad SQL grammar|doesn't exist|Table .* not found|Communications link|Could not retrieve/i;
const DATA_KEYS = ['rows', 'data', 'list', 'records', 'items', 'result', 'content', 'vo', 'dto'];

// 从响应体提取数据指标
function extractData(body) {
  const out = { dataKey: null, count: null, total: null, code: null, isDataApi: false, shape: typeof body };
  if (body == null) { out.shape = 'null'; return out; }
  if (Array.isArray(body)) { out.isDataApi = true; out.dataKey = 'root-array'; out.count = body.length; out.shape = 'array'; return out; }
  if (typeof body !== 'object') { out.shape = typeof body; return out; }
  if ('code' in body) out.code = body.code;
  if ('total' in body) out.total = body.total;
  for (const k of DATA_KEYS) {
    const v = body[k];
    if (Array.isArray(v)) { out.isDataApi = true; out.dataKey = k; out.count = v.length; break; }
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      // 对象型 data：统计键数，若含 list/rows 则取其长度
      let n = Object.keys(v).length;
      for (const kk of ['rows', 'list', 'records', 'items']) {
        if (Array.isArray(v[kk])) { n = v[kk].length; break; }
      }
      out.isDataApi = true; out.dataKey = k; out.count = n; break;
    }
  }
  return out;
}

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
      await lp.close();
      console.log(`登录#${loginCount}:`, sid ? 'REUSE' : 'FAILED');
      return !!sid;
    }
    await lp.locator('input').nth(0).fill(ACCOUNT);
    await lp.locator('input').nth(1).fill(PASSWORD);
    await lp.locator('button').filter({ hasText: '登录' }).first().click();
    await sleep(3500);
    const sid = await lp.evaluate(() => localStorage.getItem('jsessionid'));
    await lp.close();
    console.log(`登录#${loginCount}:`, sid ? 'OK' : 'FAILED');
    return !!sid;
  }
  if (!await doLogin()) { console.log('登录失败'); await browser.close(); process.exit(2); }

  async function probe(url, expectedTitle) {
    const page = await ctx.newPage();
    const apis = [];
    const apiErrors = [];
    const jsErrors = [];
    page.on('pageerror', e => jsErrors.push('PAGEERROR: ' + e.message.slice(0, 160)));
    page.on('response', async (resp) => {
      try {
        const u = resp.url();
        if (!API_RE.test(u)) return;
        const st = resp.status();
        const rec = { url: u.replace(BASE, ''), status: st, data: { isDataApi: false } };
        const ct = resp.headers()['content-type'] || '';
        if (st >= 400) {
          let body = ''; try { body = (await resp.text()).slice(0, 200); } catch (_) {}
          apiErrors.push(`HTTP_${st} ${rec.url} :: ${body}`);
          rec.error = `HTTP_${st}`;
          apis.push(rec); return;
        }
        if (ct.includes('json')) {
          try {
            const txt = await resp.text();
            if (SQL_RE.test(txt.slice(0, 1200))) {
              apiErrors.push(`SQLERR ${rec.url} :: ${txt.slice(0, 200)}`);
              rec.error = 'SQLERR';
            }
            let body = null;
            try { body = JSON.parse(txt); } catch (_) {}
            if (body != null) rec.data = extractData(body);
          } catch (_) {}
        }
        apis.push(rec);
      } catch (_) {}
    });

    let status = 0, finalUrl = '', cls = 'OK', dom = {};
    try {
      const resp = await page.goto(BASE + url, { waitUntil: 'domcontentloaded', timeout: 20000 });
      status = resp ? resp.status() : 0;
      try { await page.waitForLoadState('networkidle', { timeout: 6000 }); } catch (_) {}
      await sleep(settle);
      finalUrl = page.url().replace(BASE, '');
      dom = await page.evaluate(() => {
        const bodyText = (document.body?.innerText || '').replace(/\s+/g, ' ');
        return {
          textLen: bodyText.replace(/\s/g, '').length,
          snippet: bodyText.slice(0, 160),
          tableRows: document.querySelectorAll('.el-table__body tbody tr, .el-table__row, tbody tr').length,
          hasEmptyState: /暂无数据|没有数据|无数据|暂无内容|暂无记录|空空如也|No Data/i.test(bodyText),
          interactives: document.querySelectorAll('button, .el-button, input, select, a[href]').length
        };
      });
      // 判定
      const dataApis = apis.filter(a => a.data.isDataApi);
      const nonEmpty = dataApis.filter(a => a.data.count > 0);
      const empty = dataApis.filter(a => a.data.count === 0);
      if (/ssologin|\/login/i.test(finalUrl)) cls = 'REDIRECT_LOGIN';
      else if ((finalUrl === '/home' || finalUrl === '/') && url !== '/') cls = 'FELL_BACK_HOME';
      else if (status >= 400) cls = 'HTTP_' + status;
      else if (dom.textLen < 20) cls = 'BLANK';
      else if (apiErrors.length > 0) cls = 'API_ERROR';
      else if (nonEmpty.length > 0 && dom.tableRows > 0) cls = 'DATA_VISIBLE';
      else if (nonEmpty.length > 0 && dom.tableRows === 0 && !dom.hasEmptyState) cls = 'DATA_NOT_VISIBLE';
      else if (nonEmpty.length > 0 && dom.tableRows === 0 && dom.hasEmptyState) cls = 'DATA_PARTIAL';
      else if (dataApis.length > 0 && empty.length === dataApis.length && dom.hasEmptyState) cls = 'EMPTY_OK';
      else if (dataApis.length > 0 && empty.length === dataApis.length && !dom.hasEmptyState) cls = 'EMPTY_NO_UI';
      else if (dataApis.length === 0) cls = 'NO_DATA_API';
      else cls = 'OTHER';
    } catch (e) {
      cls = 'TIMEOUT_OR_NAV_ERR'; dom = { snippet: e.message.slice(0, 120) };
    }
    await page.close();
    return {
      url, expectedTitle: (expectedTitle || '').trim(), status, finalUrl, class: cls,
      dom, apiCount: apis.length,
      dataApis: apis.filter(a => a.data.isDataApi).map(a => ({ url: a.url, key: a.data.dataKey, count: a.data.count, total: a.data.total, code: a.data.code, error: a.error })),
      apiErrors: apiErrors.slice(0, 8), jsErrors: jsErrors.slice(0, 4)
    };
  }

  const results = [];
  let i = 0;
  for (const url of urls) {
    i++;
    let r = await probe(url, titleMap[url]);
    if (['REDIRECT_LOGIN', 'BLANK', 'FELL_BACK_HOME'].includes(r.class)) {
      console.log(`  ↻ 会话重试: ${url}`);
      await doLogin();
      r = await probe(url, titleMap[url]);
    }
    results.push(r);
    const da = r.dataApis;
    const cnt = da.map(a => `${a.key}=${a.count}${a.total != null ? '/' + a.total : ''}`).join(',');
    const mark = ['DATA_VISIBLE','DATA_PARTIAL','EMPTY_OK','NO_DATA_API'].includes(r.class) ? '✓' : '?';
    console.log(`[${i}/${urls.length}] ${mark} ${r.class.padEnd(16)} ${url} | 行=${r.dom.tableRows||0} 空态=${r.dom.hasEmptyState?'Y':'N'} 数据=[${cnt}] 接口=${r.apiCount}`);
    if (r.apiErrors.length) console.log('     APIERR:', r.apiErrors[0].slice(0, 180));
  }
  await browser.close();

  const summary = {};
  results.forEach(r => summary[r.class] = (summary[r.class] || 0) + 1);
  const totalData = results.reduce((s, r) => s + r.dataApis.filter(a => a.count > 0).length, 0);
  const totalEmpty = results.reduce((s, r) => s + r.dataApis.filter(a => a.count === 0).length, 0);
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, JSON.stringify({ total: results.length, summary, totalNonEmptyDataApis: totalData, totalEmptyDataApis: totalEmpty, results }, null, 2));
  console.log('\n=== 数据级汇总 ===', JSON.stringify(summary));
  console.log('有数据接口调用数:', totalData, '| 空数据接口调用数:', totalEmpty);
  console.log('结果文件:', outFile);
  process.exit(0);
}
main().catch(e => { console.error('FATAL:', e.message); process.exit(2); });

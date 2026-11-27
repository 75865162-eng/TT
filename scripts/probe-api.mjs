#!/usr/bin/env node
// 定向探测可疑接口：登录后用真实 token 调用，捕获响应体中的 SQL 错误
import { chromium } from 'playwright';
import fs from 'fs';
const BASE = 'http://43.143.28.153:8080';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const SQL_RE = /Unknown column|SQLSyntaxError|SQLGrammar|BadSqlGrammar|doesn't exist|NullPointer|bad SQL grammar|Table .* not found/i;

const browser = await chromium.launch({ headless: true, executablePath: '/usr/bin/chromium', args: ['--no-sandbox','--disable-dev-shm-usage'] });
const ctx = await browser.newContext();
const lp = await ctx.newPage();
await lp.goto(BASE + '/login', { waitUntil: 'domcontentloaded', timeout: 25000 });
await sleep(1200);
if (/\/login/.test(lp.url())) {
  await lp.locator('input').nth(0).fill('1');
  await lp.locator('input').nth(1).fill('1');
  await lp.locator('button').filter({ hasText: '登录' }).first().click();
  await sleep(3500);
}
const sid = await lp.evaluate(() => localStorage.getItem('jsessionid'));
console.log('SID:', sid ? sid.slice(0,10) : 'FAILED');
await lp.close();
if (!sid) { await browser.close(); process.exit(2); }

// 用浏览器上下文的 fetch 带 token（前端如何带 token 就如何带）
const probe = await ctx.newPage();
await probe.goto(BASE + '/home', { waitUntil: 'domcontentloaded' });
const tokenInfo = await probe.evaluate(() => ({
  jsessionid: localStorage.getItem('jsessionid'),
  token: localStorage.getItem('token'),
  keys: Object.keys(localStorage)
}));
console.log('LOCALSTORAGE:', JSON.stringify(tokenInfo));

// 探测接口列表
const probes = [
  { name:'交易报表-费用汇总', path:'/amazon/api/v1/fin/transactionReport/getFeeSummaryByType', method:'POST', body:{groupid:'1',marketplaceid:'ATVPDKIKX0DER',startDate:'2024-01-01',endDate:'2024-12-31'} },
  { name:'交易报表-每日费用', path:'/amazon/api/v1/fin/transactionReport/getDailyFeeByType', method:'POST', body:{groupid:'1',marketplaceid:'ATVPDKIKX0DER',startDate:'2024-01-01',endDate:'2024-12-31'} },
  { name:'交易报表-销售对比', path:'/amazon/api/v1/fin/transactionReport/getSalesCompare', method:'POST', query:{amazonauthid:'1',startDate:'2024-01-01',endDate:'2024-12-31',groupType:'week'} },
  { name:'交易报表-明细分页', path:'/amazon/api/v1/fin/transactionReport/getDetailPage', method:'POST', body:{groupid:'1',marketplaceid:'ATVPDKIKX0DER',startDate:'2024-01-01',endDate:'2024-12-31',page:1,size:10} },
];

for (const p of probes) {
  let url = BASE + p.path;
  if (p.query) url += '?' + new URLSearchParams(p.query).toString();
  const res = await probe.evaluate(async ({url, method, body, sid}) => {
    const headers = { 'Content-Type':'application/json' };
    // 前端可能的 token header 名称，都带上
    headers['jsessionid'] = sid;
    headers['token'] = sid;
    headers['Authorization'] = sid;
    try {
      const r = await fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined });
      const t = await r.text();
      return { status: r.status, body: t.slice(0, 500) };
    } catch (e) { return { status: -1, body: e.message }; }
  }, { url, method:p.method, body:p.body, sid });
  const isSql = SQL_RE.test(res.body);
  console.log(`\n[${p.name}] HTTP=${res.status} ${isSql ? '*** SQL_ERROR ***' : ''}`);
  console.log('  ', res.body.slice(0, 300).replace(/\n/g,' '));
}
await browser.close();

#!/usr/bin/env node
import { chromium } from 'playwright';
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
const probe = await ctx.newPage();
await probe.goto(BASE + '/home', { waitUntil: 'domcontentloaded' });
const probes = [
  { name:'费用汇总', path:'/amazon/api/v1/fin/transactionReport/getFeeSummaryByType', body:{groupid:'1',marketplaceid:'ATVPDKIKX0DER',startDate:'2024-01-01',endDate:'2024-12-31'} },
  { name:'每日费用', path:'/amazon/api/v1/fin/transactionReport/getDailyFeeByType', body:{groupid:'1',marketplaceid:'ATVPDKIKX0DER',startDate:'2024-01-01',endDate:'2024-12-31'} },
  { name:'销售对比', path:'/amazon/api/v1/fin/transactionReport/getSalesCompare', query:{amazonauthid:'1',startDate:'2024-01-01',endDate:'2024-12-31',groupType:'week'} },
  { name:'明细分页', path:'/amazon/api/v1/fin/transactionReport/getDetailPage', body:{groupid:'1',marketplaceid:'ATVPDKIKX0DER',startDate:'2024-01-01',endDate:'2024-12-31',page:1,size:10} },
  { name:'结转列表', path:'/erp/api/v1/purchase/alibaba/entry/purchaseAlibabaSettlement/rolloverList', body:{acct:'1',fromDate:'2024-01-01',toDate:'2024-12-31'} },
  { name:'付款汇总(period_rollover)', path:'/erp/api/v1/purchase/alibaba/entry/purchaseAlibabaSettlement/summary', body:{groupid:'1'} },
];
for (const p of probes) {
  let url = BASE + p.path;
  if (p.query) url += '?' + new URLSearchParams(p.query).toString();
  const res = await probe.evaluate(async ({url, body, sid}) => {
    const headers = { 'Content-Type':'application/json', 'jsessionid':sid, 'token':sid };
    try {
      const r = await fetch(url, { method:'POST', headers, body: body ? JSON.stringify(body) : undefined });
      return { status: r.status, body: (await r.text()).slice(0, 300) };
    } catch (e) { return { status: -1, body: e.message }; }
  }, { url, body:p.body, sid });
  const isSql = SQL_RE.test(res.body);
  console.log(`[${p.name}] HTTP=${res.status} ${isSql?'*** SQL_ERROR ***':'OK'} :: ${res.body.slice(0,120).replace(/\n/g,' ')}`);
}
await browser.close();

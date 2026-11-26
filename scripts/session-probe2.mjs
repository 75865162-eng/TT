// 精准探针：登录后完全不动(不主动调接口)，只看页面自身是否在几十秒后跳登录；
// 同时由外部 shell 并行监控该 sid 的 Redis token/accountkey 变化。
import { chromium } from 'playwright';
import fs from 'fs';
const BASE='http://43.143.28.153:8080';
const ACC=process.env.ACCEPT_ACCOUNT||'1', PWD=process.env.ACCEPT_PASSWORD||'1';
const DUR=parseInt(process.env.PROBE_DUR||'150',10);
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const b=await chromium.launch({headless:true,executablePath:'/usr/bin/chromium',args:['--no-sandbox','--disable-dev-shm-usage']});
const ctx=await b.newContext({viewport:{width:1280,height:800}});
const p=await ctx.newPage();
const evs=[];
p.on('framenavigated',f=>{if(f===p.mainFrame())evs.push({t:Date.now(),evt:'NAV',url:f.url().replace(BASE,'')});});
p.on('response',r=>{const u=r.url().replace(BASE,'');if(/\/(admin|api)\//.test(u)&&r.status()>=400)evs.push({t:Date.now(),evt:'HTTP'+r.status(),url:u.slice(0,80)});});
// 登录
await p.goto(BASE+'/login',{waitUntil:'domcontentloaded',timeout:30000});
await sleep(1200);
await p.locator('input').nth(0).fill(ACC);
await p.locator('input').nth(1).fill(PWD);
await p.locator('button').filter({hasText:'登录'}).first().click();
await sleep(3500);
const sid=await p.evaluate(()=>localStorage.getItem('jsessionid'));
const t0=Date.now();
fs.writeFileSync('/tmp/probe_sid.txt',sid+'');
console.log('LOGIN sid=',sid&&sid.slice(0,8),'t0=',t0);
await p.goto(BASE+'/home',{waitUntil:'domcontentloaded',timeout:30000});
await sleep(1000);
let droppedAt=null;
for(let i=1;i*10<=DUR;i++){
  await sleep(10000);
  const st=await p.evaluate(()=>({sid:localStorage.getItem('jsessionid'),path:location.pathname}));
  const el=(Date.now()-t0)/1000;
  console.log(`[t=${el}s] path=${st.path} sid=${st.sid&&st.sid.slice(0,8)}`);
  if(st.path==='/login'||st.path==='/ssologin'||!st.sid){droppedAt=el;break;}
}
console.log('DROPPED_AT_SEC=',droppedAt);
console.log('EVENTS=',JSON.stringify(evs.slice(0,30)));
await b.close();

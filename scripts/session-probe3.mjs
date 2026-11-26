import { chromium } from 'playwright';
import { execFileSync } from 'child_process';
const BASE='http://43.143.28.153:8080';
const ACC='1', PWD='1';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function redis(cmd){
  try{
    return execFileSync('python3',['/tmp/px.py',cmd],{env:{...process.env,SSH_PASS:'qGNX4*U[7-xv]'},encoding:'utf8',timeout:30000}).trim();
  }catch(e){return 'ERR';}
}
const b=await chromium.launch({headless:true,executablePath:'/usr/bin/chromium',args:['--no-sandbox','--disable-dev-shm-usage']});
const ctx=await b.newContext({viewport:{width:1280,height:800}});
const p=await ctx.newPage();
await p.goto(BASE+'/login',{waitUntil:'domcontentloaded',timeout:30000});
await sleep(1200);
await p.locator('input').nth(0).fill(ACC);
await p.locator('input').nth(1).fill(PWD);
await p.locator('button').filter({hasText:'登录'}).first().click();
await sleep(3500);
const sid=await p.evaluate(()=>localStorage.getItem('jsessionid'));
const t0=Date.now();
console.log('LOGIN sid=',sid&&sid.slice(0,8));
// 立即查初始状态
console.log('[t=0] token_ttl=',redis(`sudo -n docker exec wm-p0-redis redis-cli -a 123456 --no-auth-warning TTL 'login_tokens:${sid}' 2>/dev/null`),'ak=',redis(`sudo -n docker exec wm-p0-redis redis-cli -a 123456 --no-auth-warning GET '1{web}' 2>/dev/null | cut -c1-40`));
await p.goto(BASE+'/home',{waitUntil:'domcontentloaded',timeout:30000});
await sleep(1000);
for(let i=1;i<=18;i++){
  await sleep(10000);
  const st=await p.evaluate(()=>({sid:localStorage.getItem('jsessionid'),path:location.pathname}));
  const el=(Date.now()-t0)/1000;
  const ttl=redis(`sudo -n docker exec wm-p0-redis redis-cli -a 123456 --no-auth-warning TTL 'login_tokens:${sid}' 2>/dev/null`);
  const ak=redis(`sudo -n docker exec wm-p0-redis redis-cli -a 123456 --no-auth-warning GET '1{web}' 2>/dev/null | sed 's/login_tokens://' | cut -c1-8`);
  console.log(`[t=${el}s] path=${st.path} browserSid=${st.sid&&st.sid.slice(0,8)} tokenTTL=${ttl} currentAK=${ak}`);
  if(st.path==='/login'||st.path==='/ssologin'||!st.sid){console.log('>>> DROPPED @',el,'s');break;}
}
await b.close();

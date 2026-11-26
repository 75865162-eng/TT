// 会话存活探针：登录后单页停留，定时检查 jsessionid 是否失效/被替换
import { chromium } from 'playwright';
const BASE='http://43.143.28.153:8080';
const ACC=process.env.ACCEPT_ACCOUNT||'1', PWD=process.env.ACCEPT_PASSWORD||'1';
const DUR=parseInt(process.env.PROBE_DUR||'180',10); // 秒
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const b=await chromium.launch({headless:true,executablePath:'/usr/bin/chromium',args:['--no-sandbox','--disable-dev-shm-usage']});
const ctx=await b.newContext({viewport:{width:1280,height:800}});
const p=await ctx.newPage();
// 记录所有请求里的 jsessionid header 及 401/业务失效
const log=[];
p.on('response',async r=>{
  const u=r.url().replace(BASE,'');
  if(!/\/(api|admin|erp|finance|amazon)/i.test(u)) return;
  if(r.status()>=400) log.push({t:Date.now(),evt:'HTTP'+r.status(),u:u.slice(0,90)});
});
// 登录
await p.goto(BASE+'/login',{waitUntil:'domcontentloaded',timeout:30000});
await sleep(1200);
await p.locator('input').nth(0).fill(ACC);
await p.locator('input').nth(1).fill(PWD);
await p.locator('button').filter({hasText:'登录'}).first().click();
await sleep(4000);
const sid0=await p.evaluate(()=>localStorage.getItem('jsessionid'));
const t0=Date.now();
console.log('LOGIN jsessionid=',sid0&&sid0.slice(0,8),'at',new Date().toISOString());
await p.goto(BASE+'/home',{waitUntil:'domcontentloaded',timeout:30000});
await sleep(1500);

let dropped=false;
for(let i=1;i*15<=DUR;i++){
  await sleep(15000);
  const state=await p.evaluate(()=>({
    sid:localStorage.getItem('jsessionid'),
    path:location.pathname,
    hasSid:!!localStorage.getItem('jsessionid')
  }));
  const el=Date.now()-t0;
  // 主动调 route 校验会话
  let route='?';
  try{
    const r=await p.evaluate(async(sid)=>{
      const res=await fetch('/api/admin/api/v1/menus/route',{headers:{'Content-Type':'application/json;charset=utf-8','jsessionid':sid}});
      const j=await res.json();
      return `http=${res.status} code=${j&&j.code} cnt=${j&&j.data&&j.data.length}`;
    },state.sid);
    route=r;
  }catch(e){route='ERR '+e.message.slice(0,40);}
  const sidChanged = state.sid!==sid0;
  console.log(`[t=${el/1000}s] path=${state.path} sid=${state.sid&&state.sid.slice(0,8)} changed=${sidChanged} route=${route} log4xx=${log.filter(x=>x.t>t0).length}`);
  if(state.path==='/login'||state.path==='/ssologin'||!state.hasSid||sidChanged){
    console.log('  >>> 掉线/会话变更 @',el/1000,'s');
    dropped=true; break;
  }
}
console.log('RESULT dropped=',dropped,'totalSec=',DUR);
console.log('recent 4xx:',JSON.stringify(log.slice(-8)));
await b.close();

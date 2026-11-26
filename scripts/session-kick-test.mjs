// 决定性实验：账号1登录两次，验证第二次是否踢掉第一次的token
import { chromium } from 'playwright';
const B='http://43.143.28.153:8080';const sleep=m=>new Promise(r=>setTimeout(r,m));
const b=await chromium.launch({headless:true,executablePath:'/usr/bin/chromium',args:['--no-sandbox','--disable-dev-shm-usage']});
async function login(){
  const c=await b.newContext();const p=await c.newPage();
  await p.goto(B+'/login',{waitUntil:'domcontentloaded'});await sleep(1000);
  await p.locator('input').nth(0).fill('1');await p.locator('input').nth(1).fill('1');
  await p.locator('button').filter({hasText:'登录'}).first().click();await sleep(3000);
  const sid=await p.evaluate(()=>localStorage.getItem('jsessionid'));
  return {ctx:c,page:p,sid};
}
async function check(sid,label){
  const r=await fetch(B+'/api/admin/api/v1/menus/route',{headers:{'jsessionid':sid,'Content-Type':'application/json;charset=utf-8'}});
  let j;try{j=await r.json();}catch(e){j={};}
  console.log(`${label} sid=${sid&&sid.slice(0,8)} http=${r.status} code=${j&&j.code} routes=${j&&j.data&&j.data.length}`);
}
const A=await login();
console.log('A 登录后:');await check(A.sid,'A');
const B2=await login();
console.log('B 登录后:');await check(A.sid,'A');await check(B2.sid,'B');
// 浏览器页面A再发一个业务请求看是否被踢
await A.page.goto(B+'/sys/dict',{waitUntil:'domcontentloaded'});await sleep(2500);
const aPath=await A.page.evaluate(()=>location.pathname);
console.log('A页面访问/sys/dict后 path=',aPath,'sid=',await A.page.evaluate(()=>localStorage.getItem('jsessionid')&&localStorage.getItem('jsessionid').slice(0,8)));
await b.close();

// 抓取生产系统页面文本，检测 mojibake
import { chromium } from 'playwright';
import fs from 'fs';
const BASE='http://43.143.28.153:8080';
const ACC=process.env.ACCEPT_ACCOUNT||'1', PWD=process.env.ACCEPT_PASSWORD||'1';
const pages=[
  ['/sys/ruleAuth','系统角色'],
  ['/sys/subuser','管理员号'],
  ['/sys/dept','部门管理'],
  ['/sys/menu','管理菜单'],
  ['/sys/dict','数据字典'],
  ['/sys/fileuser','文件资源'],
];
// mojibake 特征：Ã/Â/å/æ/ç/è/é 后跟非ASCII控制区，或 ????? 连续问号，或 锟斤拷
const MOJI=/[ÃÂåæçèéêëïðñòóôõöùúûüýÿ][\x80-\xbf\u0080-\u00bf\u0000-\u001f]/g;
const QMARK=/[\?\?]{5,}/g;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const b=await chromium.launch({headless:true,executablePath:'/usr/bin/chromium',args:['--no-sandbox','--disable-dev-shm-usage']});
const ctx=await b.newContext({viewport:{width:1440,height:900}});
// login
const lp=await ctx.newPage();
await lp.goto(BASE+'/login',{waitUntil:'domcontentloaded',timeout:30000});
await sleep(1500);
try{
  await lp.locator('input').nth(0).fill(ACC);
  await lp.locator('input').nth(1).fill(PWD);
  await lp.locator('button').filter({hasText:'登录'}).first().click();
  await sleep(4000);
}catch(e){console.log('登录点击异常',e.message.slice(0,100));}
const sid=await lp.evaluate(()=>localStorage.getItem('jsessionid'));
console.log('登录session:',sid?sid.slice(0,8):'NONE','url:',lp.url());
await lp.close();
const result=[];
for(const [url,name] of pages){
  const p=await ctx.newPage();
  const apiErrs=[];
  p.on('response',async r=>{if(r.status()>=400)apiErrs.push(r.status()+' '+r.url().replace(BASE,''));});
  try{
    await p.goto(BASE+url,{waitUntil:'domcontentloaded',timeout:30000});
    await sleep(3000);
    const txt=await p.evaluate(()=>document.body.innerText||'');
    const mojis=[...new Set((txt.match(MOJI)||[]))];
    const qm=[...new Set((txt.match(QMARK)||[]))];
    // 抽取含 moji 的整行
    const badLines=txt.split('\n').filter(l=>MOJI.test(l)||/[\uFFFD]{2,}/.test(l)).slice(0,8);
    MOJI.lastIndex=0;
    result.push({url,name,len:txt.length,mojiCount:mojis.length,moji:mojis.slice(0,10),qm:qm.slice(0,3),badLines,badHttp:apiErrs.slice(0,5),sample:txt.slice(0,400)});
    await p.screenshot({path:`/workspace/tt/outputs/moji-${url.replace(/\//g,'_')}.png`});
  }catch(e){result.push({url,name,error:e.message.slice(0,150)});}
  await p.close();
}
console.log(JSON.stringify(result,null,2));
fs.writeFileSync('/workspace/tt/outputs/page-mojibake.json',JSON.stringify(result,null,2));
await b.close();

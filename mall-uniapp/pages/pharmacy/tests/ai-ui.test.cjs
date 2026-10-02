const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const source=fs.readFileSync(path.join(__dirname,'client.test.cjs'),'utf8').split('// ---------------- vm 加载器')[0];
const fixture=vm.createContext({require,__dirname,structuredClone,console,storage:new Map([['token','token-13900001611']])});
vm.runInContext(source+'\nglobalThis.fixture={backend,state};',fixture);
const out=path.resolve(__dirname,'../../../../.tmp/business-ai-ui');fs.mkdirSync(out,{recursive:true});
let mode='normal',requests=[];const checks=[];
(async()=>{const browser=await chromium.launch({headless:true,executablePath:process.env.UI_BROWSER_PATH});
const context=await browser.newContext({viewport:{width:375,height:844}});
await context.addInitScript(()=>{if(new URL(location.href).searchParams.get('guest')==='1') { localStorage.clear(); return; } localStorage.setItem('token','ai-ui-test-token');localStorage.setItem('firstsun-session',JSON.stringify({type:'object',data:{userId:163611,name:'测试会员',mobile:''}}));});
await context.route('**/*',async route=>{const req=route.request(),u=new URL(req.url());
if(u.pathname.startsWith('/app-api/')) {const config={url:u.pathname.replace('/app-api',''),method:req.method(),params:Object.fromEntries(u.searchParams),data:req.postDataJSON()||{},header:req.headers()};
if(config.url==='/pharmacy/ai/consult'){requests.push(config.data);await new Promise(r=>setTimeout(r,300));
if(mode==='refresh') await page.evaluate(()=>localStorage.setItem('token','ai-ui-refreshed-token'));
if(mode==='network')return route.fulfill({status:503,body:'isolated failure'});
return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:0,data:{status:mode==='pending'?'PENDING':mode==='failed'?'FAILED':'SUCCESS',answer:mode==='failed'?'AI 查询暂时不可用':mode==='pending'?'正在查询，请稍候。':'所选门店药品档案查询结果，不诊断、不开方。',source:'合成 UI 测试数据',queriedAt:'2026-10-01T12:00:00',products:mode==='normal'?[{id:163101,name:'板蓝根颗粒'+ '超长药品名'.repeat(12),price:123456.78,availableQty:999999,specification:'10袋/盒',manufacturer:'测试',imageUrl:'/static/pharmacy-demo/medicine.svg'}]:[]}})});}
const data=await fixture.fixture.backend(config);return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(data)});}
if(u.origin!=='http://127.0.0.1:4188'&&!u.protocol.startsWith('data'))return route.abort();return route.continue();});
const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
async function open(){await page.goto('http://127.0.0.1:4188/pages/pharmacy/ai');await page.locator('.ai-compose').waitFor();}
async function send(){await page.locator('textarea').fill('查询板蓝根');await page.getByText('发送',{exact:true}).click();await page.getByText('查询中',{exact:true}).waitFor();assert.equal(await page.getByText('查询中',{exact:true}).getAttribute('disabled'),'true');await page.getByText('发送',{exact:true}).waitFor();}
for(const width of [320,375,430]){await page.setViewportSize({width,height:844});await open();await send();
await page.locator('.fs-footer').scrollIntoViewIfNeeded();
const layout=await page.evaluate(()=>{const d=document.documentElement,b=document.querySelector('.fs-dock').getBoundingClientRect(),f=document.querySelector('.fs-footer').getBoundingClientRect();return {width:d.clientWidth,scroll:d.scrollWidth,dockTop:b.top,footerBottom:f.bottom};});
assert(layout.scroll<=width+1,JSON.stringify(layout));assert(layout.footerBottom<=layout.dockTop+1,JSON.stringify(layout));
await page.screenshot({path:path.join(out,`ai-${width}.png`),fullPage:true});checks.push(`layout ${width}: long product/large price, footer clear of dock`);}
await page.locator('.product-name').first().click();await page.waitForURL(/detail\?id=163101/);checks.push('AI database ID card navigates to existing product detail');
await open();mode='refresh';await send();await page.getByText('所选门店药品档案查询结果，不诊断、不开方。',{exact:true}).waitFor();checks.push('same-member token refresh retains successful result');
await open();mode='failed';await send();const failedId=requests.at(-1).clientMessageId;mode='normal';await page.getByText('重新查询',{exact:true}).click();await page.getByText('发送',{exact:true}).waitFor();assert.notEqual(requests.at(-1).clientMessageId,failedId);checks.push('explicit failure retry gets new ID');
await open();mode='pending';await send();const pendingId=requests.at(-1).clientMessageId;mode='normal';await page.getByText('查看查询结果',{exact:true}).click();await page.getByText('发送',{exact:true}).waitFor();assert.equal(requests.at(-1).clientMessageId,pendingId);checks.push('pending retry preserves ID');
await open();mode='network';await send();await page.screenshot({path:path.join(out,'ai-network-error.png'),fullPage:true});assert(await page.getByText('重新查询',{exact:true}).isVisible());checks.push('network failure is visible');
mode='normal';await page.evaluate(()=>{localStorage.removeItem('token');});await page.goto('http://127.0.0.1:4188/pages/pharmacy/ai?guest=1');await page.getByText('登录后使用购药助手',{exact:true}).waitFor();assert.equal(await page.locator('.ai-compose').count(),0);await page.screenshot({path:path.join(out,'ai-guest.png'),fullPage:true});checks.push('guest has login prompt and no composer');
assert.deepEqual(errors,[]);fs.writeFileSync(path.join(out,'report.json'),JSON.stringify({scope:'SYNTHETIC_INTERCEPTED_HTTP_UI',checks,errors},null,2));console.log(JSON.stringify({checks,out}));await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});

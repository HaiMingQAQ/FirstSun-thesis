// Isolated H5 UI acceptance. Uses the existing adapter-test HTTP fixtures, never a demo adapter.
// npm run dev:h5 -- --host 127.0.0.1 --port 4188
// PLAYWRIGHT_MODULE and UI_BROWSER_PATH may select locally installed test tooling.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const mall = path.resolve(__dirname, '../../..');
const out = path.resolve(process.env.UI_OUTPUT || path.join(mall, '../.tmp/ui-shopping'));
fs.mkdirSync(out, { recursive: true });
const source = fs.readFileSync(path.join(__dirname, 'client.test.cjs'), 'utf8').split('// ---------------- vm 加载器')[0];
const context = vm.createContext({ require, __dirname, structuredClone, console, storage: new Map([['token', 'token-13900001611']]) });
vm.runInContext(source + '\nglobalThis.fixture = { backend, state, members };', context);
const { backend, state, members } = context.fixture;
const member = members['13900001611'];
const checks = [], layouts = [], errors = [];
let scenario = 'normal', requests = [];
const origin = process.env.UI_ORIGIN || 'http://127.0.0.1:4188';
assert(['127.0.0.1','localhost'].includes(new URL(origin).hostname), 'Only local UI servers are allowed');
function resetFixture() {
  state.forceFail.clear();
  member.cart = [ {id:8101,drugId:163101,qty:1,selectedFlag:1}, {id:8102,drugId:163102,qty:2,selectedFlag:1} ];
  member.addresses = [{ id:8201, name:'界面测试收货人', mobile:'13900001611', detailAddress:'合成测试地址 · 不会配送', defaultStatus:true, areaId:350203 }];
  member.orders = [{id:8301, orderNo:'UI-TEST-8301',storeId:407,orderType:0,goodsAmount:17.8,freightAmount:0,pointDeductAmount:0,payableAmount:17.8,payStatus:0,status:0,createTime:'2026-10-01T12:00:00',lines:[{drugId:163101,drugName:'感康',specification:'12片/盒',qty:1,price:17.8}]}];
  state.drugs[0].imageUrl='/static/pharmacy/ganmaoling.jpg';
  state.drugs[0].tradeName='感康';
  state.drugs[0].memberPrice=17.8;
  state.prices.set(163101,17.8);
  state.inventory.set(163101,28);
  state.categories[0].catName='感冒用药';
}
(async () => {
 const browser = await chromium.launch({headless:true, ...(process.env.UI_BROWSER_PATH ? {executablePath:process.env.UI_BROWSER_PATH}: {})});
 const browserContext = await browser.newContext({viewport:{width:375,height:844}});
 await browserContext.route('**/*', async route => {
   const req=route.request(), url=new URL(req.url());
   if (url.pathname.startsWith('/app-api/')) {
     const config={url:url.pathname.replace('/app-api',''),method:req.method(),params:Object.fromEntries(url.searchParams),data:req.postDataJSON()||{},header:req.headers()};
     requests.push(config);
     if(scenario==='loading') await new Promise(r=>setTimeout(r,1800));
     if(scenario==='error') return route.fulfill({status:503,body:'isolated UI network failure'});
     if(config.url==='/member/wx-order/create') await new Promise(r=>setTimeout(r,700));
     let result=await backend(config);
     if(scenario==='empty') {
       if(config.url.endsWith('/page')) result={code:0,data:{list:[],total:0}};
       if(config.url.endsWith('/list') && !config.url.includes('/store/')) result={code:0,data:[]};
     }
     return route.fulfill({status:200,contentType:'application/json',headers:{'Access-Control-Allow-Origin':'*'},body:JSON.stringify(result)});
   }
   if(url.origin!==origin && !url.protocol.startsWith('data')) return route.abort('blockedbyclient');
   return route.continue();
 });
 const page=await browserContext.newPage();
 page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(() => {
   const guest=new URL(location.href).searchParams.get('uiGuest')==='1';
   localStorage.clear();
   if(!guest) {
     localStorage.setItem('token','ui-test-token');
     localStorage.setItem('firstsun-session',JSON.stringify({type:'object',data:{id:163611,name:'界面测试会员',mobile:'13900001611',level:'测试会员',points:680}}));
     localStorage.setItem('firstsun-checkout',JSON.stringify({type:'object',data:{mobile:'13900001611',buy:null,token:'ui-test-draft',prescriptions:[]}}));
   }
 });
 async function navigate(route,guest=false) {
   await page.goto(`${origin}/pages/pharmacy/${route}${route.includes('?') ? '&' : '?'}uiGuest=${guest?'1':'0'}`);
   await page.locator('.fs-page').waitFor();
   if(scenario==='loading') await page.getByText('正在加载…',{exact:true}).first().waitFor();
   else await page.waitForTimeout(550);
 }
 async function layout(name,width) {
   const result=await page.evaluate(() => {
     const clipped=el=>{for(let p=el.parentElement;p;p=p.parentElement){if(['hidden','auto','scroll'].includes(getComputedStyle(p).overflowX))return true;}return false;};
     const overflow=[...document.querySelectorAll('.fs-page *')].filter(el=>{const r=el.getBoundingClientRect();return r.width>0&&(r.right>innerWidth+1||r.left< -1)&&!clipped(el);}).map(el=>el.className||el.tagName);
     return {overflow,bodyOverflow:document.documentElement.scrollWidth>innerWidth+1};
   });
   layouts.push({name,width,...result});
   assert.equal(result.bodyOverflow,false,`${name}/${width} body overflow`);
   assert.deepEqual(result.overflow,[],`${name}/${width} element overflow`);
 }
 resetFixture();
 // Three-width normal coverage including supporting routes.
 for(const width of [320,375,430]) {
   await page.setViewportSize({width,height:844});
   for(const route of ['index','category','detail?id=163101','cart','checkout','user','order','order-detail?id=8301','address','login','prescription-upload?checkout=1']) {
     await navigate(route);
     const name=route.split('?')[0];
     await layout(name,width);
     await page.screenshot({path:path.join(out,`${name}-${width}.png`)});
     await page.evaluate(()=>window.scrollTo(0,document.documentElement.scrollHeight));
     await page.screenshot({path:path.join(out,`${name}-bottom-${width}.png`)});
   }
   await navigate('detail?id=163103');
   assert.equal(await page.getByText('立即购买',{exact:true}).count(),0);
   assert.equal(await page.getByText('加入购物车',{exact:true}).count(),0);
   assert.equal(await page.getByText('在线处方购药暂未开放',{exact:true}).getAttribute('disabled'),'true');
   await layout('rx-restricted',width);
   await page.screenshot({path:path.join(out,`rx-restricted-${width}.png`)});
   state.drugs[0].tradeName='超长药品名称与规格测试复方氨酚烷胺片仅用于布局验收'.repeat(3);
   state.categories[0].catName='超长分类名称布局验收'.repeat(3);
   state.drugs[0].memberPrice=12345678.90;state.prices.set(163101,12345678.90);
   for(const route of ['category','detail?id=163101','cart','checkout']) {
     await navigate(route);await layout('long-'+route,width);
     await page.screenshot({path:path.join(out,`long-${route.split('?')[0]}-${width}.png`)});
   }
   resetFixture();
 }
 checks.push('33 normal route layouts, Rx unavailable guards, 12 long-name/category/amount layouts');
 await page.setViewportSize({width:375,height:844});
 for(const width of [320,375,430]) {
   await page.setViewportSize({width,height:844});
   for(const mode of ['loading','empty','error']) {
     scenario=mode;
     await navigate('category');await layout('category-'+mode,width);
     await page.screenshot({path:path.join(out,`state-${mode}-${width}.png`)});
   }
   scenario='normal';
   await navigate('cart',true);await layout('guest-cart',width);
   await page.screenshot({path:path.join(out,`state-guest-${width}.png`)});
 }
 await page.setViewportSize({width:375,height:844});
 scenario='normal';
 await navigate('cart',true);
 assert(await page.getByText('登录后查看购物车',{exact:true}).isVisible());
 await page.screenshot({path:path.join(out,'state-guest.png')});
 await navigate('category',true);
 await page.locator('.add-button').first().click();
 await page.waitForURL('**/pages/pharmacy/login');
 checks.push('Anonymous browse and add-to-cart login gate');
 await navigate('category');
 await page.locator('input').fill('感康');
 await page.getByText('搜索',{exact:true}).click();await page.waitForTimeout(450);
 assert.equal(await page.locator('.fs-product').count(),1);
 await page.locator('.product-name').first().click();await page.waitForURL('**/detail?id=163101');
 await page.getByText('加入购物车',{exact:true}).click();await page.waitForTimeout(400);
 assert.equal(member.cart.find(r=>r.drugId===163101).qty,2);
 checks.push('Search → real adapter detail route → intercepted cart add');
 await navigate('category');
 await page.getByText('肠胃用药',{exact:true}).click();await page.waitForTimeout(400);
 assert.equal(await page.locator('.fs-product').count(),1);
 assert((await page.locator('.product-name').innerText()).includes('芬必得'));
 await page.getByText('全部药品',{exact:true}).click();await page.waitForTimeout(400);
 await page.getByText('非处方商品',{exact:true}).click();
 assert.equal(await page.locator('.fs-product').count(),4);
 state.inventory.set(163102,0);
 await navigate('category');
 await page.getByText('仅看有货',{exact:true}).click();
 assert.equal(await page.locator('.fs-product').count(),4);
 state.inventory.set(163102,16);
 checks.push('Category selection, in-stock and non-prescription filters');
 await navigate('cart');
 await page.locator('.fs-stepper uni-button').nth(1).click();await page.waitForTimeout(400);
 assert.equal(member.cart.find(r=>r.drugId===163101).qty,3);
 await page.locator('.cart-main > uni-button').first().click();await page.waitForTimeout(400);
 assert.equal(member.cart[0].selectedFlag,0);
 await page.locator('.cart-main > uni-button').first().click();await page.waitForTimeout(400);
 await page.getByText('去结算',{exact:true}).click();await page.waitForURL('**/checkout');
 await page.getByText('门店配送',{exact:true}).click();
 await page.locator('.fs-menu').click();await page.waitForURL('**/address?select=1');
 await page.getByText('点击选择此地址',{exact:true}).click();await page.waitForURL('**/checkout');
 await page.getByText('到店自提',{exact:true}).click();
 const before=member.orders.length;
 await page.getByText('提交订单',{exact:true}).click();
 assert.equal(await page.getByText('提交中…',{exact:true}).getAttribute('disabled'),'true');
 await page.screenshot({path:path.join(out,'state-submitting.png')});
 await page.waitForURL(/order-detail\?id=/);
 assert.equal(member.orders.length,before+1);
 assert.equal(await page.getByText('测试模拟支付',{exact:true}).count(),0);
 checks.push('Quantity/select → checkout → address selection → submit → order detail; no mock-payment button');
 await page.getByText('取消订单',{exact:true}).click();
 await page.locator('.uni-modal__btn_primary').click();await page.waitForTimeout(700);
 assert.equal(member.orders[0].status,-1);
 checks.push('Order cancellation confirmed via intercepted service, no automatic state transition');
 // Stock changes are rendered after reload, unknown IDs show a failure rather than another drug.
 state.inventory.set(163101,0);
 await navigate('detail?id=163101');assert.equal(await page.getByText('暂时缺货',{exact:true}).last().getAttribute('disabled'),'true');
 await page.screenshot({path:path.join(out,'state-stockout.png')});
 await navigate('detail?id=999999');assert(await page.getByText('暂时无法加载',{exact:true}).isVisible());
 await page.screenshot({path:path.join(out,'state-invalid.png')});
 state.drugs[1].imageUrl='/static/pharmacy/does-not-exist.jpg';
 await navigate('detail?id=163102');
 await page.waitForTimeout(300);
 assert((await page.locator('.detail-hero img').getAttribute('src')).includes('medicine-placeholder.png'));
 await page.screenshot({path:path.join(out,'state-image-fallback.png')});
 checks.push('Stockout disables buying, invalid ID shows error, failed image falls back');
 assert.deepEqual(errors,[], 'Browser page errors');
 fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({scope:'ISOLATED_SYNTHETIC_HTTP_UI_ONLY',checks,layouts,errors,requestCount:requests.length},null,2));
 console.log(JSON.stringify({checks,layoutCount:layouts.length,errors,out},null,2));
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1;process.exit(1);});

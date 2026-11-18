const {chromium,expect}=require(process.env.FIRSTSUN_PLAYWRIGHT_PACKAGE||'playwright/test');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const site=process.env.FIRSTSUN_CUSTOMER_PREVIEW_URL||'http://127.0.0.1:4197';
const output=path.resolve(__dirname,'../../../.local/ui-review-remediation');
const profile={id:8001,nickname:'界面验收会员',loginEmail:'viewer@example.test',email:'contact@example.test',passwordSet:true,mobile:'',levelName:'普通会员'};
const drug={id:163187,tradeName:'龙珠软膏',genericName:'龙珠软膏',specification:'15g×1支/盒',manufacturer:'验收厂家',isRx:0,memberPrice:16.9,retailPrice:16.9,imageUrl:'/assets/firstsun-home-products.png'};
const cart=[{id:11,storeId:9101,drugId:drug.id,drugName:drug.genericName,specification:drug.specification,unit:'盒',qty:2,memberPrice:16.9,retailPrice:16.9,selectedFlag:1,isRx:0}];
const order={id:8101,orderNo:'UI-ONLY-8101',storeId:9101,orderType:0,status:0,payStatus:0,goodsAmount:33.8,discountAmount:0,couponAmount:0,pointDeductAmount:0,freightAmount:0,payableAmount:33.8,expireAt:Date.now()+3600000,lines:[{...cart[0],price:16.9,lineAmount:33.8,catalogImageUrl:drug.imageUrl}]};

async function fixture(browser,{guest=false,unknownOrder=false,unknownPayment=false,addressFailure=false}={}){
 const context=await browser.newContext({viewport:{width:1440,height:1000}});
 if(!guest)await context.addInitScript(()=>sessionStorage.setItem('token','ui-only-fixture'));
 const page=await context.newPage();
 const errors=[],unexpected=[],writes=[];
 const state={paid:false,capability:true,orderReadFailure:false,cart:structuredClone(cart),addressFailure};
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',async route=>{
  const req=route.request(),u=new URL(req.url()),p=u.pathname,method=req.method();
  if(u.origin!==site){unexpected.push(method+' '+u.origin+p);return route.abort();}
  if(!p.startsWith('/app-api/')){
   if(method==='GET')return route.continue();
   unexpected.push(method+' '+p);return route.abort();
  }
  const ok=data=>route.fulfill({json:{code:0,data}});
  const activeOrder={...order,status:state.paid?1:0,payStatus:state.paid?1:0};
  if(method!=='GET')writes.push({method,path:p,data:req.postDataJSON()});
  if(p==='/app-api/pharmacy/store/list')return ok([{id:9101,storeName:'FirstSun 验收门店',address:'验收地址'}]);
  if(p==='/app-api/pharmacy/drug/get')return ok(drug);
  if(p==='/app-api/pharmacy/drug/category-list')return ok([]);
  if(p==='/app-api/pharmacy/drug/page')return ok({list:[drug],total:1});
  if(p==='/app-api/pharmacy/inventory/available')return ok([{drugId:drug.id,qtyAvail:24}]);
  if(p==='/app-api/member/user/get')return ok(profile);
  if(p==='/app-api/member/business-notification/unread')return ok(0);
  if(p==='/app-api/member/business-notification/page')return ok({list:[],total:0});
  if(p==='/app-api/member/wx-cart/list')return ok(state.cart);
  if(p==='/app-api/member/address/list')return state.addressFailure?route.fulfill({json:{code:503,msg:'地址暂时无法读取'}}):ok([{id:91,name:'验收收件人',mobile:'13900000000',detailAddress:'仅用于界面验收的收货地址',defaultStatus:true}]);
  if(p==='/app-api/member/auth/email-password-login')return ok({accessToken:'ui-only-after-login'});
  if(p==='/app-api/member/consultation/page')return ok({list:[],total:0});
  if(p==='/app-api/member/consultation/open')return ok(31);
  if(p==='/app-api/member/consultation/messages')return ok({conversation:{id:31,kind:'PHARMACIST',storeId:9101,attended:false},list:[],hasMore:false});
  if(p==='/app-api/member/consultation/send')return ok(41);
  if(p==='/app-api/member/consultation/read')return ok(true);
  if(p==='/app-api/member/wx-order/create'){
   if(unknownOrder)return route.abort('connectionfailed');
   state.cart=[];return ok(order.id);
  }
  if(p==='/app-api/member/wx-order/get'){
   const id=Number(u.searchParams.get('id'));
   if(id===8101&&state.orderReadFailure)return route.abort('connectionfailed');
   return ok({...activeOrder,id,status:id===8101?activeOrder.status:0,payStatus:id===8101?activeOrder.payStatus:0});
  }
  if(p==='/app-api/member/wx-order/page')return ok({list:[activeOrder],total:1});
  if(p==='/app-api/member/wx-order/line-list')return ok(order.lines);
  if(p==='/app-api/member/wx-order/mock-payment-available')return ok(state.capability);
  if(p==='/app-api/member/wx-order/simulate-pay'){
   if(unknownPayment)return route.abort('connectionfailed');
   state.paid=true;return ok(true);
  }
  unexpected.push(method+' '+p);return route.abort();
 });
 return{page,context,state,writes,errors,unexpected};
}
async function screenshot(page,name){await page.screenshot({path:path.join(output,name+'.png'),fullPage:true,animations:'disabled'});}
async function noOverflow(page){assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));}
async function responsive(page,name){
 for(const width of [390,1280,1920]){
  await page.setViewportSize({width,height:1000});await noOverflow(page);
  if(width===390)await screenshot(page,name+'-mobile');
 }
 await page.setViewportSize({width:1440,height:1000});
}


(async()=>{
 await fs.mkdir(output,{recursive:true});
 const browser=await chromium.launch({headless:true});
 const fixtures=[];
 try{
  const consult=await fixture(browser,{guest:true});fixtures.push(consult);
  const p=consult.page;
  await p.goto(site+'/#detail?id='+drug.id);
  await expect(p.locator('.buy-actions').getByRole('button',{name:'咨询药师',exact:true})).toBeVisible();
  await p.getByRole('button',{name:'说明书',exact:true}).click();
  await screenshot(p,'customer-f05-leaflet');
  await responsive(p,'customer-f05-leaflet');
  await p.locator('.leaflet-empty').getByRole('button',{name:'咨询药师',exact:true}).click();
  await expect(p.locator('.consult-product')).toContainText(drug.specification);
  await expect(p.getByRole('dialog')).toHaveCount(0);
  await expect(p.getByText('了解药品信息、说明书和用药注意事项。')).toBeVisible();
  assert.equal(consult.writes.length,0);
  await screenshot(p,'customer-f05-guest');
  await responsive(p,'customer-f05-guest');
  await p.getByRole('button',{name:'联系药师',exact:true}).click();
  await expect(p.getByRole('dialog')).toBeVisible();
  await p.getByLabel('邮箱',{exact:true}).fill('viewer@example.test');
  await p.getByLabel('密码',{exact:true}).fill('fixture-only-password');
  await p.getByRole('dialog').getByRole('button',{name:'登录',exact:true}).click();
  await expect(p.getByRole('dialog')).toHaveCount(0);
  await expect(p.locator('.consult-product')).toContainText(drug.specification);
  await p.getByRole('button',{name:'联系药师',exact:true}).click();
  await expect(p.getByRole('textbox',{name:'咨询内容'})).toHaveValue('我想咨询龙珠软膏（15g×1支/盒）。');
  assert(!consult.writes.some(w=>w.path.endsWith('/consultation/send')));
  await screenshot(p,'customer-f05-context-draft');
  await p.getByRole('button',{name:'发送',exact:true}).click();
  await expect.poll(()=>consult.writes.filter(w=>w.path.endsWith('/consultation/send')).length).toBe(1);
  const sent=consult.writes.find(w=>w.path.endsWith('/consultation/send'));
  assert.equal(sent.data.content,'我想咨询龙珠软膏（15g×1支/盒）。');
  for(const width of [390,1280,1920]){await p.setViewportSize({width,height:1000});await noOverflow(p);}
  await consult.context.close();

  const checkout=await fixture(browser);fixtures.push(checkout);
  await checkout.page.goto(site+'/#checkout');
  await expect(checkout.page.getByRole('button',{name:'提交订单',exact:true})).toBeEnabled();
  await expect(checkout.page.locator('.summary-box')).toContainText('¥0.00（到店自提）');
  await expect(checkout.page.locator('.checkout-next')).toHaveText('提交后进入付款步骤');
  await screenshot(checkout.page,'customer-f11-checkout');
  await responsive(checkout.page,'customer-f11-checkout');
  await checkout.page.getByRole('button',{name:'提交订单',exact:true}).click();
  await checkout.page.waitForURL(/#order-detail\?id=8101$/);
  await expect(checkout.page.getByRole('button',{name:'模拟支付',exact:true})).toBeVisible();
  await checkout.page.getByRole('button',{name:'模拟支付',exact:true}).click();
  await expect(checkout.page.getByRole('dialog')).toContainText('不会扣款');
  await screenshot(checkout.page,'customer-f11-cashier');
  assert.equal(checkout.writes.filter(w=>w.path.endsWith('/simulate-pay')).length,0);
  await checkout.page.getByRole('button',{name:'确认模拟支付',exact:true}).click();
  await expect(checkout.page.locator('.order-stage h2')).toHaveText('已支付 · 等待门店拣货');
  assert.equal(checkout.writes.filter(w=>w.path.endsWith('/create')).length,1);
  await screenshot(checkout.page,'customer-f11-paid');
  await responsive(checkout.page,'customer-f11-paid');
  await checkout.context.close();

  const unknown=await fixture(browser,{unknownOrder:true});fixtures.push(unknown);
  await unknown.page.goto(site+'/#checkout');
  await unknown.page.getByRole('button',{name:'提交订单',exact:true}).click();
  await expect(unknown.page.locator('.trade-error')).toContainText('未确认下单结果');
  await expect(unknown.page.getByRole('button',{name:'提交订单',exact:true})).toBeDisabled();
  await screenshot(unknown.page,'customer-f11-unknown-order');
  assert.equal(unknown.writes.filter(w=>w.path.endsWith('/create')).length,1);
  await unknown.context.close();

  const uncertain=await fixture(browser,{unknownPayment:true});fixtures.push(uncertain);
  await uncertain.page.goto(site+'/#order-detail?id=8101');
  await uncertain.page.getByRole('button',{name:'模拟支付',exact:true}).click();
  await uncertain.page.getByRole('button',{name:'确认模拟支付',exact:true}).click();
  await expect(uncertain.page.locator('.trade-error')).toContainText('未确认付款结果');
  await expect(uncertain.page.locator('.order-stage h2')).toHaveText('付款结果待核实');
  await expect(uncertain.page.getByRole('button',{name:'取消待付订单',exact:true})).toBeDisabled();
  await expect(uncertain.page.locator('.order-stage')).not.toContainText('未支付');
  await expect(uncertain.page.getByRole('button',{name:'模拟支付',exact:true})).toBeDisabled();
  await expect(uncertain.page.getByRole('link',{name:'去我的订单核对',exact:true})).toBeVisible();
  await screenshot(uncertain.page,'customer-f11-unknown-payment');
  await responsive(uncertain.page,'customer-f11-unknown-payment');
  uncertain.state.orderReadFailure=true;
  await uncertain.page.getByRole('button',{name:'刷新订单',exact:true}).click();
  await expect(uncertain.page.locator('.order-stage h2')).toHaveText('付款结果待核实');
  await expect(uncertain.page.getByRole('button',{name:'取消待付订单',exact:true})).toBeDisabled();
  await expect(uncertain.page.getByRole('button',{name:'模拟支付',exact:true})).toBeDisabled();
  await expect(uncertain.page.locator('.order-stage')).not.toContainText('未支付');
  await screenshot(uncertain.page,'customer-f11-unknown-refresh-failed');
  await uncertain.page.goto(site+'/#order-detail?id=8102');
  await expect(uncertain.page.locator('.order-stage h2')).toHaveText('待支付');
  await expect(uncertain.page.getByRole('button',{name:'取消待付订单',exact:true})).toBeEnabled();
  await uncertain.page.goto(site+'/#orders');
  await expect(uncertain.page.locator('[data-order-id="8101"] .order-state-tag')).toContainText('付款结果待核实');
  await expect(uncertain.page.locator('[data-order-id="8101"] .order-method')).toContainText('付款结果待核实');
  uncertain.state.orderReadFailure=false;
  uncertain.state.paid=true;
  await uncertain.page.goto(site+'/#order-detail?id=8101');
  await expect(uncertain.page.locator('.order-stage h2')).toHaveText('已支付 · 等待门店拣货');
  await expect(uncertain.page.getByRole('button',{name:'取消待付订单',exact:true})).toHaveCount(0);
  await expect(uncertain.page.getByRole('button',{name:'模拟支付',exact:true})).toHaveCount(0);
  await screenshot(uncertain.page,'customer-f11-unknown-resolved-paid');
  assert.equal(uncertain.writes.filter(w=>w.path.endsWith('/simulate-pay')).length,1);
  assert.equal(uncertain.writes.filter(w=>w.path.endsWith('/cancel')).length,0);
  await uncertain.context.close();

  const unpaid=await fixture(browser,{unknownPayment:true});fixtures.push(unpaid);
  await unpaid.page.goto(site+'/#order-detail?id=8101');
  await unpaid.page.getByRole('button',{name:'模拟支付',exact:true}).click();
  await unpaid.page.getByRole('button',{name:'确认模拟支付',exact:true}).click();
  await expect(unpaid.page.locator('.order-stage h2')).toHaveText('付款结果待核实');
  await unpaid.page.getByRole('button',{name:'刷新订单',exact:true}).click();
  await expect(unpaid.page.locator('.order-stage h2')).toHaveText('待支付');
  await expect(unpaid.page.getByRole('button',{name:'取消待付订单',exact:true})).toBeEnabled();
  await expect(unpaid.page.getByRole('button',{name:'模拟支付',exact:true})).toBeEnabled();
  await screenshot(unpaid.page,'customer-f11-unknown-resolved-unpaid');
  await unpaid.context.close();

  const paidRefresh=await fixture(browser,{unknownPayment:true});fixtures.push(paidRefresh);
  await paidRefresh.page.goto(site+'/#order-detail?id=8101');
  await paidRefresh.page.getByRole('button',{name:'模拟支付',exact:true}).click();
  await paidRefresh.page.getByRole('button',{name:'确认模拟支付',exact:true}).click();
  await expect(paidRefresh.page.locator('.order-stage h2')).toHaveText('付款结果待核实');
  paidRefresh.state.paid=true;
  await paidRefresh.page.getByRole('button',{name:'刷新订单',exact:true}).click();
  await expect(paidRefresh.page.locator('.order-stage h2')).toHaveText('已支付 · 等待门店拣货');
  await expect(paidRefresh.page.getByRole('button',{name:'取消待付订单',exact:true})).toHaveCount(0);
  await expect(paidRefresh.page.getByRole('button',{name:'模拟支付',exact:true})).toHaveCount(0);
  await screenshot(paidRefresh.page,'customer-f11-unknown-resolved-paid');
  await paidRefresh.context.close();

  const disabled=await fixture(browser);fixtures.push(disabled);
  await disabled.page.goto(site+'/#order-detail?id=8101');
  await disabled.page.getByRole('button',{name:'模拟支付',exact:true}).click();
  disabled.state.capability=false;
  await disabled.page.getByRole('button',{name:'确认模拟支付',exact:true}).click();
  await expect(disabled.page.locator('.trade-error')).toContainText('当前环境未开放测试模拟支付');
  assert.equal(disabled.writes.filter(w=>w.path.endsWith('/simulate-pay')).length,0);
  await disabled.context.close();

  const account=await fixture(browser,{addressFailure:true});fixtures.push(account);
  await account.page.goto(site+'/#account');
  await expect(account.page.getByRole('button',{name:'管理登录方式',exact:true})).toBeVisible();
  await expect(account.page.getByLabel('原密码',{exact:true})).toHaveCount(0);
  await expect(account.page.getByLabel('联系邮箱（不用于登录）',{exact:true})).toHaveValue(profile.email);
  await expect(account.page.getByRole('link',{name:'业务通知'})).toHaveAttribute('href','#notifications');
  await screenshot(account.page,'customer-f12-account');
  await account.page.getByRole('button',{name:'管理登录方式',exact:true}).click();
  await expect(account.page.getByLabel('原密码',{exact:true})).toBeVisible();
  await responsive(account.page,'customer-f12-security-expanded');
  await account.page.getByRole('button',{name:'收起登录方式',exact:true}).click();
  await account.page.getByRole('button',{name:'收货地址',exact:true}).click();
  await expect(account.page.locator('#account-addresses')).toContainText('地址暂时无法读取');
  account.state.addressFailure=false;
  await account.page.locator('#account-addresses').getByRole('button',{name:'重试',exact:true}).click();
  await expect(account.page.locator('#account-addresses')).toContainText('仅用于界面验收的收货地址');
  for(const width of [390,1280,1920]){await account.page.setViewportSize({width,height:1000});await noOverflow(account.page);}
  await account.page.setViewportSize({width:390,height:1000});
  await screenshot(account.page,'customer-f12-account-mobile');
  assert.equal(account.writes.length,0);
  await account.context.close();

  for(const f of fixtures){assert.deepEqual(f.errors,[]);assert.deepEqual(f.unexpected,[]);}
  const result={passed:true,scenarios:['f05 guest browse, login return, product draft, explicit send','f11 checkout, simulated confirmation, uncertain payment/cancel gates, failed refresh persistence, confirmed paid/unpaid recovery, order isolation','f12 collapsed security, email label, address retry, service links','390/1280/1440/1920 overflow'],realBusinessRequests:0,scriptErrors:[]};
  await fs.writeFile(path.join(output,'customer-f05-f11-f12-result.json'),JSON.stringify(result,null,2));
  console.log(JSON.stringify(result,null,2));
 }finally{await browser.close();}
})().catch(error=>{console.error(error.stack);process.exitCode=1;});

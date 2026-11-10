import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {parse,compileScript} from '@vue/compiler-sfc';

// Execute the actual App setup with controlled promises. No HTTP, login or business writes.
const app=fs.readFileSync(new URL('../src/App.vue',import.meta.url),'utf8');
const card=fs.readFileSync(new URL('../src/ProductCard.vue',import.meta.url),'utf8');
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return{promise,resolve,reject};};
const flush=async()=>{for(let n=0;n<15;n++)await Promise.resolve();};
const ids=h=>Array.from(h.items.value,p=>p.id);
const rows=qty=>[{id:11,drugId:42,qty}];
function setup(api={},trade={}){
 let state={token:'A',tenant:'901',epoch:1};const unmounted=[];
 const context={ref:value=>({value}),computed:fn=>({get value(){return fn();}}),watch(){},nextTick:fn=>Promise.resolve().then(fn),onMounted(){},onUnmounted:fn=>unmounted.push(fn),
  api:{store:{id:9101,name:'Fixture store'},loadCategories:async()=>[],productPage:async({pageNo})=>({list:Array.from({length:9},(_,i)=>({id:(pageNo-1)*9+i+1})),total:18}),product:async id=>({id}),...api},
  trade:{reset(){},cart:async()=>rows(2),add:async()=>{},quantity:async()=>{},remove:async()=>{},...trade},account:{logout:async()=>{}},identity:()=>({...state}),sameIdentity:(a,b)=>a.token===b.token&&a.tenant===b.tenant&&a.epoch===b.epoch,clearLoginState(){},localTestLogin:false,
  window:{scrollTo(){},addEventListener(){},removeEventListener(){}},document:{body:{dataset:{}},addEventListener(){},removeEventListener(){}},location:{hash:'#catalog'},history:{replaceState(){}},crypto:{randomUUID:()=> 'fixture-id'},URLSearchParams,
  setTimeout:()=>0,clearTimeout(){},setInterval:()=>0,clearInterval(){}};
 const script=app.split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'');
 vm.runInNewContext(script+'\nglobalThis.view={items,count,page,route,query,category,loadCatalog,loadMore,parseRoute,search,pickCategory,user,cartQtyById,cartCount,refreshCart,decreaseCart,addToCart,adding,dialog,catalogError,catalogLoading,resetPrivate};',context);
 const h={...context.view,context,switchMember(){state={token:'B',tenant:'901',epoch:state.epoch+1};h.user.value={userId:2};},dispose(){unmounted.forEach(fn=>fn());}};
 h.user.value={userId:1};h.cartQtyById.value={42:{rowId:11,qty:3}};h.cartCount.value=3;return h;
}
test('returning from detail resets to page one and can load all pages again',async()=>{
 const h=setup();await h.loadCatalog();await h.loadMore();assert.equal(h.items.value.length,18);h.route.value='detail';h.context.location.hash='#catalog';h.parseRoute();await flush();assert.equal(h.page.value,1);assert.deepEqual(ids(h),[1,2,3,4,5,6,7,8,9]);await h.loadMore();assert.deepEqual(ids(h),Array.from({length:18},(_,i)=>i+1));
});
test('append commits page only on success; failure keeps rows/page and retries the same page',async()=>{
 let fail=true;const calls=[],h=setup({productPage:async({pageNo})=>{calls.push(pageNo);if(pageNo===2&&fail)throw Error('append offline');return{list:[{id:pageNo}],total:18};}});await h.loadCatalog();await h.loadMore();assert.equal(h.page.value,1);assert.deepEqual(ids(h),[1]);assert.equal(h.catalogError.value,'append offline');assert.equal(h.catalogLoading.value,false);fail=false;await h.loadMore();assert.deepEqual(calls,[1,2,2]);assert.equal(h.page.value,2);assert.deepEqual(ids(h),[1,2]);
});
test('pending append keeps the successful page number and blocks another append',async()=>{
 const wait=deferred(),calls=[],h=setup({productPage:({pageNo})=>{calls.push(pageNo);return pageNo===1?Promise.resolve({list:[{id:1}],total:18}):wait.promise;}});await h.loadCatalog();const pending=h.loadMore();await h.loadMore();assert.equal(h.page.value,1);assert.deepEqual(calls,[1,2]);wait.resolve({list:[{id:2}],total:18});await pending;assert.equal(h.page.value,2);
});
for(const trigger of ['search','category'])for(const outcome of ['success','failure'])test(`old append ${outcome} cannot write new ${trigger}`,async()=>{
 const old=deferred();let n=0;const h=setup({productPage:()=>++n===1?Promise.resolve({list:[{id:1}],total:18}):n===2?old.promise:Promise.resolve({list:[{id:99}],total:1})});await h.loadCatalog();const pending=h.loadMore();if(trigger==='search'){h.query.value='new';h.search();}else h.pickCategory('new');await flush();if(outcome==='success')old.resolve({list:[{id:2}],total:18});else old.reject(Error('old error'));await pending;assert.deepEqual(ids(h),[99]);assert.equal(h.page.value,1);assert.equal(h.count.value,1);assert.equal(h.catalogError.value,'');assert.equal(h.catalogLoading.value,false);
});
test('catalog late response after unmount cannot publish rows',async()=>{const wait=deferred(),h=setup({productPage:()=>wait.promise});const pending=h.loadCatalog();h.dispose();wait.resolve({list:[{id:1}],total:1});await pending;assert.deepEqual(ids(h),[]);});
test('editing input without submitting still releases the latest request loading',async()=>{
 const wait=deferred(),h=setup({productPage:()=>wait.promise});const pending=h.loadCatalog();h.query.value='unsubmitted input';wait.resolve({list:[{id:1}],total:1});await pending;assert.equal(h.catalogLoading.value,false);assert.deepEqual(ids(h),[]);
});
test('older catalog completion cannot release a newer pending search loading',async()=>{
 const old=deferred(),newer=deferred();let reads=0;const h=setup({productPage:()=>++reads===1?old.promise:newer.promise});const pending=h.loadCatalog();h.query.value='new search';h.search();old.resolve({list:[{id:1}],total:1});await pending;assert.equal(h.catalogLoading.value,true);newer.resolve({list:[{id:99}],total:1});await flush();assert.equal(h.catalogLoading.value,false);assert.deepEqual(ids(h),[99]);
});
test('append failure leaves visible products and a loadMore retry in actual template',()=>{
 const template=app.split('<template>')[1];assert(template.includes('v-if="catalogError&&!items.length"'));assert(template.includes('v-else-if="catalogLoading&&!items.length"'));assert(template.includes('v-if="catalogError" class="error-panel"'));assert(template.includes('@click="loadMore"'));
});
test('rapid decreases dispatch one write and next click uses refreshed quantity',async()=>{
 const wait=deferred(),writes=[],h=setup({}, {quantity:async(store,row,qty)=>{writes.push([store,row,qty]);if(writes.length===1)await wait.promise;},cart:async()=>rows(writes.length===1?2:1)});
 const a=h.decreaseCart({id:42}),b=h.decreaseCart({id:42});assert.equal(h.adding.value,true);assert.deepEqual(writes,[[9101,11,2]]);wait.resolve();await Promise.all([a,b]);assert.equal(h.adding.value,false);await h.decreaseCart({id:42});assert.deepEqual(writes,[[9101,11,2],[9101,11,1]]);
});
for(const first of ['decrease','add'])test(`${first} write excludes the other mutation until readback completes`,async()=>{
 const write=deferred(),read=deferred(),calls=[];const h=setup({}, {quantity:()=>{calls.push('decrease');return write.promise;},add:()=>{calls.push('add');return write.promise;},cart:()=>read.promise});
 const pending=first==='add'?h.addToCart({id:42}):h.decreaseCart({id:42}),other=first==='add'?h.decreaseCart({id:42}):h.addToCart({id:42});assert.deepEqual(calls,[first]);write.resolve();await flush();assert.equal(h.adding.value,true);read.resolve(rows(2));await Promise.all([pending,other]);assert.equal(h.adding.value,false);
});
test('remove at quantity one uses real row ID and the same lock',async()=>{const wait=deferred(),calls=[],h=setup({}, {remove:(...args)=>{calls.push(args);return wait.promise;}});h.cartQtyById.value={42:{rowId:11,qty:1}};const pending=h.decreaseCart({id:42}),other=h.decreaseCart({id:42});assert.deepEqual(calls,[[9101,11]]);assert(h.adding.value);wait.resolve();await Promise.all([pending,other]);assert.equal(h.adding.value,false);});
test('failed decrease releases lock and can retry without changing contract',async()=>{let calls=0;const h=setup({}, {quantity:async()=>{if(++calls===1)throw Error('write failed');}});await h.decreaseCart({id:42});assert.equal(h.adding.value,false);assert.equal(h.dialog.value,'write failed');await h.decreaseCart({id:42});assert.equal(calls,2);assert.equal(h.adding.value,false);});
test('old member write completion cannot unlock a new member write',async()=>{
 const a=deferred(),b=deferred();let calls=0;const h=setup({}, {quantity:()=>++calls===1?a.promise:b.promise});const old=h.decreaseCart({id:42});h.resetPrivate();h.switchMember();h.cartQtyById.value={42:{rowId:22,qty:4}};const newer=h.decreaseCart({id:42});a.reject(Error('old failed'));await old;assert.equal(h.dialog.value,'');assert.equal(h.adding.value,true);b.resolve();await newer;assert.equal(h.adding.value,false);
});
test('identity change without a new write does not leave busy locked',async()=>{const wait=deferred(),h=setup({}, {quantity:()=>wait.promise});const pending=h.decreaseCart({id:42});h.switchMember();wait.resolve();await pending;assert.equal(h.adding.value,false);});
test('card decrement button follows the shared adding busy state',()=>{const opening=card.match(/<button\b[^>]*aria-label="减少数量"[^>]*>/)?.[0];assert(opening);assert.match(opening,/:disabled="adding"/);});
test('only latest same-member cart read can publish',async()=>{const a=deferred(),b=deferred();let reads=0;const h=setup({}, {cart:()=>++reads===1?a.promise:b.promise});const old=h.refreshCart(),newer=h.refreshCart();b.resolve(rows(3));await newer;a.resolve(rows(1));await old;assert.equal(h.cartCount.value,3);assert.equal(h.cartQtyById.value[42].qty,3);});
test('latest cart read failure clears old counts; older success cannot revive them',async()=>{const a=deferred(),b=deferred();let reads=0;const h=setup({}, {cart:()=>++reads===1?a.promise:b.promise});const old=h.refreshCart(),newer=h.refreshCart();b.reject(Error('latest unavailable'));await newer;assert.equal(h.cartCount.value,0);assert.deepEqual(Object.keys(h.cartQtyById.value),[]);a.resolve(rows(1));await old;assert.equal(h.cartCount.value,0);});
test('old cart read failure cannot clear a newer success',async()=>{const a=deferred(),b=deferred();let reads=0;const h=setup({}, {cart:()=>++reads===1?a.promise:b.promise});const old=h.refreshCart(),newer=h.refreshCart();b.resolve(rows(4));await newer;a.reject(Error('old failed'));await old;assert.equal(h.cartCount.value,4);});
for(const change of ['member','store','unmount','reset'])test(`late cart success after ${change} is ignored`,async()=>{
 const wait=deferred(),h=setup({}, {cart:()=>wait.promise});const pending=h.refreshCart();if(change==='member')h.switchMember();if(change==='store')h.context.api.store={id:9102};if(change==='unmount')h.dispose();if(change==='reset'){h.resetPrivate();h.user.value={userId:1};}h.cartCount.value=8;h.cartQtyById.value={42:{rowId:88,qty:8}};wait.resolve(rows(1));await pending;assert.equal(h.cartCount.value,8);assert.equal(h.cartQtyById.value[42].rowId,88);
});
test('write invalidates older cart read before its own latest readback',async()=>{
 const oldRead=deferred(),write=deferred();let reads=0;const h=setup({}, {cart:()=>++reads===1?oldRead.promise:Promise.resolve(rows(2)),quantity:()=>write.promise});const old=h.refreshCart(),pending=h.decreaseCart({id:42});oldRead.resolve(rows(1));await old;assert.notEqual(h.cartCount.value,1);write.resolve();await pending;assert.equal(h.cartCount.value,2);
});
for(const [name,source] of [['App.vue',app],['ProductCard.vue',card]])test(`${name} compiles with its actual template after regression fix`,()=>{const {descriptor,errors}=parse(source,{filename:name});assert.deepEqual(errors,[]);assert.doesNotThrow(()=>compileScript(descriptor,{id:name,inlineTemplate:true}));});

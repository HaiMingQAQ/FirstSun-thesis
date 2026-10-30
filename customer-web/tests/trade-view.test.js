import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as core from '../src/trade-core.js';
import {sameIdentity} from '../src/guards.js';
// Controlled SFC lifecycle/identity tests; these do not claim real HTTP timing.
function harness(route){
 let state={token:'A',tenant:'901',epoch:1},resolve,mount,dispose,called=false;
 const promise=new Promise(done=>resolve=done),props={route,orderId:'101',user:{userId:1}};
 const delayed=()=>{called=true;return promise;};
 const trade={cart:route==='cart'?delayed:async()=>[],addresses:delayed,orders:delayed,order:delayed,available:async()=>[],capability:async()=>false,creation:()=>null};
 const context={...core,ref:value=>({value}),computed:fn=>({get value(){return fn();}}),onMounted:fn=>mount=fn,onUnmounted:fn=>dispose=fn,defineProps:()=>props,defineEmits:()=>()=>{},catalog:{store:{id:9101}},trade,identity:()=>({...state}),sameIdentity,localAcceptance:false,window:{scrollTo(){}},location:{hash:''},formatDate:()=>''};
 const source=fs.readFileSync(new URL('../src/TradePages.vue',import.meta.url),'utf8').split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'');vm.runInNewContext(source+'\nglobalThis.view={rows,addresses,orders,order};',context);
 return{...context.view,mount:()=>mount(),dispose:()=>dispose(),called:()=>called,resolve,change:mode=>{state={token:mode==='logout'?'':'B',tenant:'901',epoch:2};props.user=mode==='logout'?null:{userId:2};}};
}
for(const route of ['cart','checkout','orders','order-detail'])for(const mode of ['logout','switch','unmount'])test(`late ${route} response after ${mode} cannot write private view`,async()=>{
 const h=harness(route),pending=h.mount();for(let n=0;n<10&&!h.called();n++)await Promise.resolve();assert(h.called());if(mode==='unmount')h.dispose();else h.change(mode);
 h.resolve(route==='orders'?{list:[{id:101}],total:1}:route==='order-detail'?{id:101}:[{id:11,storeId:9101,drugId:9303}]);await pending;
 assert.equal(h.rows.value.length,0);assert.equal(h.addresses.value.length,0);assert.equal(h.orders.value.length,0);assert.equal(h.order.value,null);
});

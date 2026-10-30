import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as Vue from 'vue';
import {renderToString} from 'vue/server-renderer';
import {compile} from '@vue/compiler-dom';
import {parse,compileScript} from '@vue/compiler-sfc';
import {formatDate} from '../../mall-uniapp/sheep/api/pharmacy/common.js';
import {assertImageSize,assertMaterialBytes,recordState,rejectionReason,reviewLabel,reviewNote,useLabel} from '../src/prescription-core.js';

const jpeg=Uint8Array.of(0xff,0xd8,0xff,0xd9);
const png=Uint8Array.of(0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,1);
const source=fs.readFileSync(new URL('../src/PrescriptionPages.vue',import.meta.url),'utf8');
const template=source.slice(source.indexOf('<template>')+10,source.lastIndexOf('</template>'));
function render(state){
 const app={components:{Icon:{render:()=>null}},setup:()=>state,render:new Function('Vue',compile(template,{mode:'function',prefixIdentifiers:true}).code)(Vue)};
 return renderToString(Vue.createSSRApp(app));
}
function harness(route='prescriptions',query=''){
 let state={token:'A',tenant:'901',epoch:1};const props={route,query,user:{userId:1}},events=[],listeners=new Map();let mount,dispose,urls=0;
 const prescription={calls:[],resets:0,listResult:[],detailResult:null,async ensureStore(){return {id:9101,name:'当前门店',address:'测试路'}},async list(){prescription.calls.push('list');return prescription.listResult},async detail(id){prescription.calls.push(['detail',id]);return prescription.detailResult},async drug(id){return {id,name:'预选药',specification:'10mg',rx:true}},async search(word){prescription.calls.push(['search',word]);return [{id:5,name:'搜索药',specification:'盒',rx:true}]},async upload(file,storeId){prescription.calls.push(['upload',storeId,file.name]);return 41},async material(id){prescription.calls.push(['material',id]);return {mime:'image/jpeg',bytes:jpeg}},async submit(body){prescription.calls.push(['submit',body]);return 9},reset(){prescription.resets++},submission:()=>null};
 const context={ref:Vue.ref,onMounted:fn=>mount=fn,onUnmounted:fn=>dispose=fn,defineProps:()=>props,defineEmits:()=> (...args)=>events.push(args),prescription,assertImageSize,assertMaterialBytes,recordState,rejectionReason,reviewLabel,reviewNote,useLabel,formatDate,identity:()=>({...state}),sameIdentity:(a,b)=>a.token===b.token&&a.tenant===b.tenant&&a.epoch===b.epoch,URLSearchParams,Blob,URL:{createObjectURL(){return 'blob:private-'+(++urls)},revokeObjectURL(){}},location:{hash:''},window:{addEventListener(name,fn){listeners.set(name,fn)},removeEventListener(name){listeners.delete(name)}},Number,Uint8Array};
 vm.runInNewContext(source.split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'')+'\nglobalThis.view={records,record,store,files,items,form,results,keyword,previewUrl,previewError,previewLoading,busy,searching,searchError,loadError,actionError,notice,uncertain,loading,load,submit,search,addFiles,preview,onAuthClear};',context);
 return {...context.view,prescription,events,props,mount:()=>mount(),dispose:()=>dispose(),switch(){state={token:'B',tenant:'901',epoch:2};props.user={userId:2}},logout(){state={token:'',tenant:'901',epoch:2};props.user=null},clear:()=>listeners.get('firstsun-auth-clear')?.(),location:context.location};
}
const flush=async h=>{const pending=h.mount();for(let n=0;n<8;n++)await Promise.resolve();return pending;};

test('prescription page compiles and renders real rejection and approval fields',()=>{
 const {descriptor,errors}=parse(source,{filename:'PrescriptionPages.vue'});assert.deepEqual(errors,[]);assert.doesNotThrow(()=>compileScript(descriptor,{id:'rx',inlineTemplate:true}));
});

test('guest does not read prescriptions and an empty list is not a failure',async()=>{
 const guest=harness();guest.props.user=null;await flush(guest);assert.deepEqual(guest.prescription.calls,[]);assert.equal(guest.records.value.length,0);guest.dispose();
 const h=harness();h.prescription.listResult=[];await flush(h);assert.equal(h.loading.value,false);assert.equal(h.loadError.value,'');assert.equal(h.records.value.length,0);
 const html=await render({...h,reviewLabel,reviewNote,rejectionReason,recordState,useLabel,formatDate,route:'prescriptions',user:{userId:1},busy:{value:false},searching:{value:false},searchError:{value:''},previewUrl:{value:''},previewError:{value:''},previewLoading:{value:false},keyword:{value:''},otherStore:()=>false,load(){},submit(){},search(){},selectDrug(){},removeItem(){},setQty(){},removeFile(){},chooseFiles(){},emit(){}});
 assert.match(html,/暂无处方申请/);assert.doesNotMatch(html,/state-tag[^>]*>审核通过/);h.dispose();
});

for(const mode of ['logout','switch','unmount'])test(`late prescription list after ${mode} is discarded`,async()=>{
 let resolve,called=false;const h=harness();h.prescription.list=()=>new Promise(ok=>{called=true;resolve=ok});const pending=h.mount();for(let n=0;n<8&&!called;n++)await Promise.resolve();assert(called);
 if(mode==='unmount')h.dispose();else if(mode==='logout')h.logout();else h.switch();
 resolve([{id:1,patientName:'别人',reviewStatus:1,approvedItems:[{drugName:'伪造核准'}]}]);await pending;
 assert.equal(h.records.value.length,0);assert.equal(h.record.value,null);
});

test('detail renders server rejection and does not turn requested items into approval',async()=>{
 const h=harness('rx-detail','id=8');h.prescription.detailResult={id:8,prescNo:'APP-8',storeId:9101,patientName:'顾晨',hospital:'市医院',doctorName:'张医生',createTime:1,reviewStatus:2,reviewOpinion:'剂量与处方不符',status:0,materialIds:[4],requestedItems:[{drugId:5,qty:2,drugName:'申请药',specification:'10mg',usage:'',dosage:''}],approvedItems:[],approvedUntil:null,uses:[]};
 await flush(h);assert.equal(h.record.value.reviewOpinion,'剂量与处方不符');
 const html=await render({...h,reviewLabel,reviewNote,rejectionReason,recordState,useLabel,formatDate,route:'rx-detail',user:{userId:1},busy:{value:false},searching:{value:false},searchError:{value:''},keyword:{value:''},results:h.results,otherStore:()=>false,load(){},submit(){},search(){},preview:h.preview,selectDrug(){},removeItem(){},setQty(){},removeFile(){},chooseFiles(){},emit(){}});
 assert.match(html,/驳回原因/);assert.match(html,/剂量与处方不符/);assert.match(html,/申请药/);assert.match(html,/不是药师核准结果/);assert.doesNotMatch(html,/<h2>核准明细<\/h2>/);assert.doesNotMatch(html,/按核准明细结算|去结算|模拟支付/);h.dispose();
});

test('approved detail shows only returned approval lines and keeps purchase out of this page',async()=>{
 const h=harness('rx-detail','id=9');h.prescription.detailResult={id:9,prescNo:'APP-9',storeId:9102,patientName:'顾晨',createTime:2,reviewStatus:1,reviewOpinion:'',status:0,materialIds:[],requestedItems:[{drugId:5,qty:3,drugName:'申请药',specification:'',usage:'',dosage:''}],approvedItems:[{drugId:5,qty:1,drugName:'核准药',specification:'5mg',usage:'口服',dosage:'每日一次'}],approvedUntil:3,uses:[{wxOrderId:11,status:'PAID',releaseReason:'',createTime:4}]};
 await flush(h);
 const html=await render({...h,reviewLabel,reviewNote,rejectionReason,recordState,useLabel,formatDate,route:'rx-detail',user:{userId:1},busy:{value:false},searching:{value:false},searchError:{value:''},keyword:{value:''},previewUrl:{value:''},previewError:{value:''},previewLoading:{value:false},otherStore:row=>Number(row.storeId)!==9101,load(){},submit(){},search(){},preview(){},selectDrug(){},removeItem(){},setQty(){},removeFile(){},chooseFiles(){},emit(){}});
 assert.match(html,/核准明细/);assert.match(html,/核准药 × 1/);assert.match(html,/口服/);assert.match(html,/每日一次/);assert.match(html,/非当前门店/);assert.match(html,/购买与付款不在本页办理/);assert.match(html,/已支付使用/);assert.match(html,/查看订单 11/);assert.doesNotMatch(html,/无法确认|去结算|模拟支付|药师意见/);h.dispose();
});

test('missing approval lines are not copied from the request',async()=>{
 const h=harness('rx-detail','id=10');h.prescription.detailResult={id:10,prescNo:'APP-10',storeId:9101,patientName:'顾晨',createTime:2,reviewStatus:1,status:0,materialIds:[],requestedItems:[{drugId:5,qty:3,drugName:'申请药',specification:'',usage:'',dosage:''}],approvedItems:[],uses:[]};
 await flush(h);const html=await render({...h,reviewLabel,reviewNote,rejectionReason,recordState,useLabel,formatDate,route:'rx-detail',user:{userId:1},busy:{value:false},searching:{value:false},searchError:{value:''},keyword:{value:''},previewUrl:{value:''},previewError:{value:''},previewLoading:{value:false},otherStore:()=>false,load(){},submit(){},search(){},preview(){},selectDrug(){},removeItem(){},setQty(){},removeFile(){},chooseFiles(){},emit(){}});
 assert.match(html,/未返回核准明细/);assert.match(html,/不会根据申请内容补写/);h.dispose();
});

test('submit uploads the current store once and a late upload cannot navigate',async()=>{
 const h=harness('rx-submit');await flush(h);h.form.value.patientName='顾晨';h.items.value=[{drugId:5,qty:2,name:'药',specification:'盒'}];const file={name:'a.png',size:png.length,arrayBuffer:async()=>png.buffer};h.files.value=[{key:'a',name:'a.png',id:null,storeId:null,localUrl:'blob:1',file}];
 await h.submit();assert.deepEqual(h.prescription.calls.filter(call=>Array.isArray(call)&&call[0]==='upload'),[['upload',9101,'a.png']]);assert.deepEqual(JSON.parse(JSON.stringify(h.prescription.calls.at(-1)[1].items)),[{drugId:5,qty:2}]);assert.equal(h.location.hash,'rx-detail?id=9');
 const late=harness('rx-submit');await flush(late);late.form.value.patientName='顾晨';late.items.value=[{drugId:5,qty:1,name:'药'}];const held=file;late.files.value=[{key:'b',name:'b.png',id:null,storeId:null,localUrl:'blob:2',file:held}];let release;late.prescription.upload=()=>new Promise(ok=>{release=ok});const pending=late.submit();for(let n=0;n<6&&!release;n++)await Promise.resolve();late.switch();release(41);await pending;assert.equal(late.location.hash,'');assert.equal(late.files.value[0].id,null);h.dispose();late.dispose();
});

test('store change blocks submit and rejected prefill keeps the original material only for that store',async()=>{
 const h=harness('rx-submit');await flush(h);h.prescription.ensureStore=async()=>({id:9102,name:'新门店',address:''});h.form.value.patientName='顾晨';h.items.value=[{drugId:5,qty:1}];h.files.value=[{key:'a',id:4,storeId:9101,localUrl:'blob:1',file:null}];await h.submit();assert.match(h.actionError.value,/门店已变化/);assert.equal(h.files.value[0].id,null);assert.equal(h.prescription.calls.some(call=>call[0]==='submit'),false);
 const from=harness('rx-submit','from=8');from.prescription.detailResult={id:8,storeId:9101,patientName:'顾晨',hospital:'市医院',doctorName:'张医生',reviewStatus:2,status:0,materialIds:[4],requestedItems:[{drugId:5,qty:2,drugName:'申请药',specification:'10mg'}],approvedItems:[{drugName:'不应带入'}]};
 await flush(from);assert.equal(from.form.value.patientName,'顾晨');assert.equal(from.items.value[0].name,'申请药');assert.equal(from.files.value[0].id,4);assert.equal(from.items.value.some(item=>item.name==='不应带入'),false);
 const other=harness('rx-submit','from=8');other.prescription.detailResult={...from.prescription.detailResult,storeId:9102};await flush(other);assert.match(other.notice.value,/其他门店/);assert.equal(other.files.value.length,0);h.dispose();from.dispose();other.dispose();
});

test('invalid image and late search cannot be presented as uploaded or current',async()=>{
 const h=harness('rx-submit');await flush(h);await h.addFiles([{name:'x.svg',size:5,arrayBuffer:async()=>new TextEncoder().encode('<svg></svg>').buffer}]);assert.match(h.actionError.value,/PNG\/JPEG/);assert.equal(h.files.value.length,0);
 let release;h.prescription.search=()=>new Promise(ok=>{release=ok});const pending=h.search();h.keyword.value='药';const again=h.search();h.switch();release([{id:5,name:'旧结果'}]);await Promise.allSettled([pending,again]);assert.equal(h.results.value.length,0);h.dispose();
});

test('auth clear drops private preview state',async()=>{
 const h=harness('rx-detail','id=8');h.prescription.detailResult={id:8,prescNo:'APP-8',storeId:9101,patientName:'顾晨',createTime:1,reviewStatus:0,status:0,materialIds:[4],requestedItems:[],approvedItems:[],uses:[]};await flush(h);await h.preview(4);assert.match(h.previewUrl.value,/blob:private/);h.clear();assert.equal(h.records.value.length,0);assert.equal(h.record.value,null);assert.equal(h.previewUrl.value,'');assert.equal(h.prescription.resets,1);h.dispose();
});

test('desktop entries open prescription pages without removing ordinary cart behavior',()=>{
 const app=fs.readFileSync(new URL('../src/App.vue',import.meta.url),'utf8');
 assert(app.includes('href="#rx-submit"'));assert(app.includes("href=\"'#rx-submit?drugId='+detail.id\""));assert(app.includes('href="#prescriptions"'));
 assert(!app.includes('处方申请 · 未接入'));assert(!app.includes("notConnected('处方申请')"));assert(!app.includes("notConnected('我的处方')"));
 assert(app.includes("notConnected('消息中心')"));assert(app.includes("notConnected('药师 / 客服咨询')"));
 assert(app.includes(':disabled="adding||stockWarning||detail.stock<=0||detail.device"'));
});

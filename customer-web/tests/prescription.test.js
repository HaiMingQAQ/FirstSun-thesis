import test from 'node:test';
import assert from 'node:assert/strict';
import {createPrescriptionApi,validateApplication,reviewLabel,reviewNote,rejectionReason,recordState,useLabel,assertMaterialBytes,assertImageSize,imageKind,normalizeRecord,sortRecords,submissionSnapshot} from '../src/prescription-core.js';

const png=Uint8Array.of(0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,0,0);
const jpeg=Uint8Array.of(0xff,0xd8,0xff,0xd9);
const svg=new TextEncoder().encode('<svg xmlns="http://www.w3.org/2000/svg"></svg>');
const deferred=()=>{let resolve,reject;const promise=new Promise((ok,no)=>{resolve=ok;reject=no;});return {promise,resolve,reject};};
const payload=(patch={})=>({storeId:3,patientName:'顾晨',hospital:'市医院',doctorName:'张医生',materialIds:[4],items:[{drugId:5,qty:2,usage:'不许代填'}],...patch});

function harness(extra={}){
 let ident={token:'A',tenant:'901',epoch:1},clears=0,seq=0;const calls=[];
 const catalog={store:{id:3,name:'测试门店',address:'路 1 号'},async refreshStore(){catalog.store={id:3,name:'测试门店',address:''};},async product(id){return {id,name:'数据库药名',specification:'10mg',rx:true};},async productPage(){return {list:[{id:5,name:'数据库药名',specification:'10mg',rx:true},{id:6,name:'第二味',specification:'',isRx:0}]};},...extra.catalog};
 const api=createPrescriptionApi({request:async config=>{calls.push(config);return extra.request?extra.request(config):{code:0,data:extra.data??[]};},fetch:extra.fetch||(async()=>{throw Error('未期望的文件请求');}),identity:()=>({...ident}),clearLoginState(){clears++;ident={...ident,token:'',epoch:ident.epoch+1};},FormData:globalThis.FormData,catalog,now:()=>1000,random:()=>'id'+(++seq)});
 return {api,calls,catalog,clears:()=>clears,ident:()=>({...ident}),switch(){ident={token:'B',tenant:'901',epoch:ident.epoch+1};},logout(){ident={...ident,token:'',epoch:ident.epoch+1};}};
}

test('review text follows server status and does not invent approval',()=>{
 const pending={reviewStatus:0,status:0,reviewOpinion:'',requestedItems:[{drugName:'申请药'}],approvedItems:[]};
 const rejected={reviewStatus:2,status:0,reviewOpinion:'  剂量与处方不符  '};
 const approved={reviewStatus:1,status:0,reviewOpinion:'',approvedItems:[]};
 const unknown={reviewStatus:9,status:0};
 assert.equal(reviewLabel(pending),'待药师审核');assert.match(reviewNote(pending),/等待药师审核/);assert.equal(rejectionReason(pending),'');
 assert.equal(reviewLabel(rejected),'审核未通过');assert.equal(rejectionReason(rejected),'剂量与处方不符');assert.equal(reviewNote(rejected),'剂量与处方不符');
 assert.equal(reviewLabel({reviewStatus:2,status:0,reviewOpinion:'   '}),'审核未通过');assert.equal(rejectionReason({reviewStatus:2,status:0,reviewOpinion:' '}),'药师未填写驳回原因');
 assert.equal(reviewLabel(approved),'审核通过');assert.equal(reviewNote(approved),'');assert.equal(reviewLabel({reviewStatus:1,status:2}),'已作废');assert.match(reviewNote({status:2,reviewStatus:1}),/已作废/);
 assert.equal(reviewLabel(unknown),'状态待确认');assert.match(reviewNote(unknown),/无法确认/);assert.equal(recordState({status:1}),'已完成');assert.equal(useLabel('HELD'),'待支付占用');assert.equal(useLabel('OTHER'),'使用状态待确认');
});

test('records keep requested and approved lists separate and sort by server time',()=>{
 const row=normalizeRecord({id:7,prescNo:'APP-1',storeId:3,patientName:'顾晨',createTime:2,reviewStatus:1,status:0,reviewOpinion:'同意',materialIds:[4,'bad'],requestedItems:[{drugId:5,qty:2,drugName:'申请名',usage:'患者自填'}],approvedItems:[],approvedUntil:null,uses:[{wxOrderId:11,status:'RELEASED',releaseReason:'超时',createTime:3}]});
 assert.equal(row.approvedItems.length,0);assert.equal(row.requestedItems[0].drugName,'申请名');assert.equal(row.requestedItems[0].usage,'患者自填');assert.deepEqual(row.materialIds,[4]);assert.equal(row.uses[0].wxOrderId,11);
 assert.throws(()=>normalizeRecord({id:'7'}),/编号无法确认/);
 assert.deepEqual(sortRecords([{id:1,createTime:1},{id:3,createTime:5},{id:2,createTime:5}]).map(row=>row.id),[3,2,1]);
});

test('application fields match the member create contract',()=>{
 const valid=validateApplication(payload());
 assert.deepEqual(valid.items,[{drugId:5,qty:2}]);assert.equal(submissionSnapshot(valid).includes('不许代填'),false);
 for (const patch of [{patientName:'  '},{patientName:'名'.repeat(33)},{hospital:'医'.repeat(101)},{storeId:0},{materialIds:[]},{materialIds:[1,1]},{materialIds:[1,2,3,4]},{items:[]},{items:[{drugId:5,qty:0}]},{items:[{drugId:5,qty:1000}]},{items:[{drugId:5,qty:1},{drugId:5,qty:2}]},{items:Array.from({length:21},(_,index)=>({drugId:index+1,qty:1}))}]) assert.throws(()=>validateApplication(payload(patch)));
 assert.equal(imageKind(png),'image/png');assert.equal(imageKind(jpeg),'image/jpeg');assert.equal(imageKind(svg),'');
 assert.throws(()=>assertMaterialBytes(svg,svg.length),/PNG\/JPEG/);assert.throws(()=>assertMaterialBytes(png,5*1024*1024+1),/5MB/);assert.doesNotThrow(()=>assertImageSize(10,10));assert.throws(()=>assertImageSize(8001,1),/尺寸/);
});

test('list and detail require an array or numeric record and skip token refresh',async()=>{
 const h=harness({data:[{id:8,createTime:1,reviewStatus:0,status:0,patientName:'甲',approvedItems:null,requestedItems:[{drugId:5,qty:1,drugName:'甲药'}]}]});
 const rows=await h.api.list();
 assert.equal(rows[0].approvedItems.length,0);assert.equal(rows[0].requestedItems[0].drugName,'甲药');assert.equal(h.calls[0].custom.skipRefresh,true);assert.equal(h.calls[0].url,'/member/prescription/page');
 const one=harness();one.api=createWrapped(one,async()=>({code:0,data:{id:8,reviewStatus:2,status:0,reviewOpinion:'不符',requestedItems:[],approvedItems:[]}}));
 const detail=await one.api.detail(8);assert.equal(detail.reviewOpinion,'不符');assert.equal(one.calls[0].params.id,8);
 await assert.rejects(h.api.detail('8'),/链接无效/);
 const bad=harness({request:async()=>({code:0,data:{total:1}})});await assert.rejects(bad.api.list(),/列表返回异常/);
});

function createWrapped(h,request){return createPrescriptionApi({request:async config=>{h.calls.push(config);return request(config);},fetch:async()=>{throw Error('未期望的文件请求');},identity:()=>h.ident(),clearLoginState(){},FormData,catalog:h.catalog,now:()=>1000,random:()=>'once'});}

test('failed or changed identity cannot receive another member list',async()=>{
 const wait=deferred();const h=harness({request:()=>wait.promise});const pending=h.api.list();h.switch();wait.resolve({code:0,data:[{id:1,reviewStatus:1,status:0,approvedItems:[{drugName:'别人的药'}]}]});
 await assert.rejects(pending,/账号已变化/);assert.equal(h.api.submission(),null);
});

test('same submission reuses request id and changed or conflicting content does not',async()=>{
 const h=harness({request:async config=>{if(h.calls.length===1)throw Error('请求超时，请重试');if(config.data.patientName==='冲突')return {code:400,msg:'重复申请编号与原内容不一致'};return {code:0,data:9};}});
 await assert.rejects(h.api.submit(payload()),/超时/);assert.equal(h.api.submission().uncertain,true);assert.equal(h.api.submission().requestId,'presc_1000_id1');
 assert.equal(await h.api.submit(payload()),9);assert.equal(h.calls[1].data.clientRequestId,'presc_1000_id1');assert.deepEqual(h.calls[1].data.items,[{drugId:5,qty:2}]);assert.equal(h.calls[1].data.storeId,3);assert.equal(h.api.submission(),null);
 assert.equal(await h.api.submit(payload({patientName:'新患者'})),9);assert.equal(h.calls[2].data.clientRequestId,'presc_1000_id2');
 await assert.rejects(h.api.submit(payload({patientName:'冲突'})),/不一致/);assert.equal(h.api.submission(),null);
 assert.equal(await h.api.submit(payload({patientName:'冲突后'})),9);assert.equal(h.calls[4].data.clientRequestId,'presc_1000_id4');assert.notEqual(h.calls[3].data.clientRequestId,h.calls[4].data.clientRequestId);
});

test('server rejection is not uncertain and a second submit waits for the first',async()=>{
 const h=harness({request:async()=>({code:400,msg:'药品不支持当前在线处方申请'})});
 await assert.rejects(h.api.submit(payload()),/不支持/);assert.equal(h.api.submission().uncertain,false);assert.equal(h.api.submission().requestId,'presc_1000_id1');
 const wait=deferred();const slow=harness({request:()=>wait.promise});const first=slow.api.submit(payload());await Promise.resolve();await assert.rejects(slow.api.submit(payload()),/正在提交/);wait.resolve({code:0,data:12});assert.equal(await first,12);
 const failed=harness({request:async()=>({code:500,msg:'server failed'})});await assert.rejects(failed.api.submit(payload()),error=>error.uncertain===true);assert.equal(failed.api.submission().uncertain,true);
});

test('submit does not dispatch for another store and upload only accepts a real material id',async()=>{
 const h=harness();await assert.rejects(h.api.submit(payload({storeId:9})),/门店已变化/);assert.equal(h.calls.length,0);
 const uploaded=[];const ok=harness({fetch:async(url,options)=>{uploaded.push([url,options]);return {status:200,ok:true,text:async()=>JSON.stringify({code:0,data:41})};}});
 const file=new File([png],'note.png',{type:'image/png'});assert.equal(await ok.api.upload(file,3),41);assert.equal(uploaded[0][0],'/app-api/member/prescription/material/upload');assert.equal(uploaded[0][1].body.get('storeId'),'3');assert.equal(uploaded[0][1].headers['Content-Type'],undefined);assert.equal(uploaded[0][1].headers.Authorization,'A');
 const rejected=harness({fetch:async()=>({status:200,ok:true,text:async()=>JSON.stringify({code:400,msg:'图片格式不合法'})})});await assert.rejects(rejected.api.upload(file,3),/格式不合法/);
 const late=harness({fetch:()=>new Promise(resolve=>setTimeout(()=>resolve({status:200,ok:true,text:async()=>JSON.stringify({code:0,data:41})}),20))});const pending=late.api.upload(file,3);late.switch();await assert.rejects(pending,/账号已变化/);
});

test('private material bytes stay gated and a stale login is cleared only for that identity',async()=>{
 const h=harness({fetch:async()=>({status:200,ok:true,arrayBuffer:async()=>jpeg.buffer})});const image=await h.api.material(4);assert.equal(image.mime,'image/jpeg');
 const denied=harness({fetch:async()=>({status:200,ok:true,arrayBuffer:async()=>svg.buffer})});await assert.rejects(denied.api.material(4),/材料加载失败|格式|权限/);
 const forbidden=harness({fetch:async()=>({status:403,ok:false,arrayBuffer:async()=>new TextEncoder().encode(JSON.stringify({code:403,msg:'无材料访问权限'})).buffer})});await assert.rejects(forbidden.api.material(4),/无材料访问权限/);
 const expired=harness({fetch:async()=>({status:401,ok:false,arrayBuffer:async()=>new ArrayBuffer(0)})});await assert.rejects(expired.api.material(4),error=>error.code===401);assert.equal(expired.clears(),1);assert.equal(expired.ident().token,'');
 const moved=harness({fetch:()=>new Promise(resolve=>setTimeout(()=>resolve({status:401,ok:false,arrayBuffer:async()=>new ArrayBuffer(0)}),20))});const pending=moved.api.material(4);moved.switch();await assert.rejects(pending,/账号已变化/);assert.equal(moved.clears(),0);assert.equal(moved.ident().token,'B');
});

test('drug search is limited to the current store response and drops stale results',async()=>{
 const h=harness();assert.equal((await h.api.search(' 药 ')).length,2);assert.equal((await h.api.search(' 药 '))[0].name,'数据库药名');
 const wait=deferred();const slow=harness({catalog:{async productPage(){return wait.promise;}}});const pending=slow.api.search('药');slow.switch();wait.resolve({list:[{id:5,name:'旧门店'}]});await assert.rejects(pending,/账号已变化/);
});

test('prescription json 401 does not refresh the token',async()=>{
 const store=new Map();globalThis.__TENANT_ID__='901';globalThis.sessionStorage={getItem:key=>store.has(key)?store.get(key):null,setItem:(key,value)=>store.set(key,value),removeItem:key=>store.delete(key)};globalThis.window={dispatchEvent(){}};globalThis.location={origin:'http://127.0.0.1:4190'};
 const session=await import('../src/session.js');const request=(await import('../src/request.js')).default;session.clearLoginState();session.setTokens({accessToken:'rx-token',refreshToken:'rx-refresh'});
 const urls=[];globalThis.fetch=async url=>{urls.push(String(url));return {ok:true,status:200,json:async()=>({code:401})};};
 await assert.rejects(request({url:'/member/prescription/page'}),error=>error.code===401);assert.equal(urls.length,1);assert.equal(urls[0].includes('refresh-token'),false);assert.equal(session.identity().token,'');
});

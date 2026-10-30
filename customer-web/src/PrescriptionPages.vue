<script setup>
import {onMounted,onUnmounted,ref} from 'vue';
import prescription from './prescription.js';
import {assertImageSize,assertMaterialBytes,recordState,rejectionReason,reviewLabel,reviewNote,useLabel} from './prescription-core.js';
import {formatDate} from '@/sheep/api/pharmacy/common';
import {identity} from './session.js';
import {sameIdentity} from './guards.js';
const props=defineProps({route:String,query:String,user:Object});
const emit=defineEmits(['login','notice']);
const records=ref([]),record=ref(null),store=ref(null),loading=ref(false),busy=ref(false),loadError=ref(''),actionError=ref(''),notice=ref('');
const form=ref({patientName:'',hospital:'',doctorName:''}),items=ref([]),files=ref([]),keyword=ref(''),results=ref([]),searching=ref(false),searchError=ref('');
const previewUrl=ref(''),previewError=ref(''),previewLoading=ref(false),uncertain=ref(false);
let disposed=false,generation=0,searchGeneration=0,previewGeneration=0;
const ownedUrls=[];
const owner=identity();
const current=()=>!disposed&&!!props.user&&sameIdentity(owner,identity());
const params=()=>new URLSearchParams(props.query||'');
function revoke(url){if(url)URL.revokeObjectURL(url);}
function revokeAll(){for(const file of files.value)revoke(file.localUrl);revoke(previewUrl.value);previewUrl.value='';for(const url of ownedUrls.splice(0))revoke(url);}
function onAuthClear(){generation++;searchGeneration++;previewGeneration++;revokeAll();records.value=[];record.value=null;files.value=[];items.value=[];results.value=[];form.value={patientName:'',hospital:'',doctorName:''};previewError.value='';loadError.value='';actionError.value='';notice.value='';uncertain.value=false;prescription.reset();}
function materialFile(id,material,storeId){const localUrl=URL.createObjectURL(new Blob([material.bytes],{type:material.mime}));ownedUrls.push(localUrl);return {key:`server-${id}`,name:`已保存材料 ${id}`,id,storeId,localUrl,file:null};}
function addItem(product){if(!product?.id)return;if(items.value.some(item=>item.drugId===product.id)){results.value=[];return;}if(items.value.length>=20)throw Error('单次申请最多 20 项药品');items.value=[...items.value,{drugId:product.id,qty:1,name:product.name||'未命名药品',specification:product.specification||''}];results.value=[];}
function selectDrug(product){if(busy.value||!current())return;try{addItem(product);actionError.value='';}catch(e){actionError.value=e.message;}}
function removeItem(drugId){if(busy.value)return;items.value=items.value.filter(item=>item.drugId!==drugId);}
function setQty(item,event){const qty=Number(event.target.value);if(!Number.isInteger(qty)||qty<1||qty>999){event.target.value=item.qty;actionError.value='每种药品数量须为 1–999';return;}item.qty=qty;actionError.value='';}
function removeFile(index){if(busy.value)return;const file=files.value[index];if(!file)return;revoke(file.localUrl);files.value=files.value.filter((_,currentIndex)=>currentIndex!==index);}
async function addFiles(list){if(busy.value||!current())return;const incoming=[...(list||[])].slice(0,Math.max(0,3-files.value.length));if(!incoming.length)return;actionError.value='';for(const file of incoming){try{const bytes=new Uint8Array(await file.arrayBuffer());if(!current())return;assertMaterialBytes(bytes,file.size);if(typeof createImageBitmap==='function'){const bitmap=await createImageBitmap(file);try{assertImageSize(bitmap.width,bitmap.height);}finally{bitmap.close?.();}if(!current())return;}const localUrl=URL.createObjectURL(file);ownedUrls.push(localUrl);files.value=[...files.value,{key:`local-${ownedUrls.length}-${file.name||'image'}`,name:file.name||'处方图片',id:null,storeId:null,localUrl,file}];}catch(e){if(current())actionError.value=e.message;}}}
function chooseFiles(event){const selected=[...(event.target.files||[])];event.target.value='';addFiles(selected);}
async function prefill(g,currentStore){
 const drugId=params().get('drugId'),from=params().get('from');
 if(from){
  if(!/^\d+$/.test(from))throw Error('原申请链接无效');
  const source=await prescription.detail(Number(from));
  if(!current()||g!==generation)return;
  if(Number(source.reviewStatus)!==2||Number(source.status)===2)throw Error('只有审核未通过的申请可以带入内容后重新提交');
  if(Number(source.storeId)!==Number(currentStore.id))notice.value='原材料属于其他门店，请重新选择图片。文字和药品已带入，提交后属于当前门店。';
  form.value={patientName:source.patientName||'',hospital:source.hospital||'',doctorName:source.doctorName||''};
  items.value=source.requestedItems.filter(item=>Number.isSafeInteger(item.drugId)&&item.drugId>0).map(item=>({drugId:item.drugId,qty:Number.isInteger(item.qty)&&item.qty>=1&&item.qty<=999?item.qty:1,name:item.drugName,specification:item.specification||''}));
  if(Number(source.storeId)===Number(currentStore.id)&&source.materialIds.length){
   const downloaded=[];
   try{
    for(const id of source.materialIds){const material=await prescription.material(id);if(!current()||g!==generation){downloaded.forEach(file=>revoke(file.localUrl));return;}downloaded.push(materialFile(id,material,currentStore.id));}
    if(current()&&g===generation)files.value=downloaded;
   }catch(e){downloaded.forEach(file=>revoke(file.localUrl));if(current()&&g===generation)notice.value=`原处方图片暂时无法读取，请重新选择。${e.message}`;}
  }
 }
 if(drugId){if(!/^\d+$/.test(drugId))throw Error('药品链接无效');const drug=await prescription.drug(Number(drugId));if(!current()||g!==generation)return;addItem(drug);}
}
async function load(){
 if(!props.user)return;
 const g=++generation;loading.value=true;loadError.value='';notice.value='';
 try{
  const currentStore=await prescription.ensureStore();
  if(!current()||g!==generation)return;
  store.value=currentStore;
  if(props.route==='prescriptions'){const rows=await prescription.list();if(!current()||g!==generation)return;records.value=rows;}
  else if(props.route==='rx-detail'){const id=params().get('id');if(!/^\d+$/.test(id||''))throw Error('申请链接无效');const row=await prescription.detail(Number(id));if(!current()||g!==generation)return;record.value=row;}
  else await prefill(g,currentStore);
 }catch(e){if(current()&&g===generation)loadError.value=e.message;}
 finally{if(current()&&g===generation)loading.value=false;}
}
async function search(){
 const g=++searchGeneration;const word=keyword.value.trim();searchError.value='';
 if(!word||!current()){results.value=[];searching.value=false;return;}
 searching.value=true;
 try{const rows=await prescription.search(word);if(!current()||g!==searchGeneration)return;results.value=rows;if(!rows.length)searchError.value='未找到药品';}
 catch(e){if(current()&&g===searchGeneration){results.value=[];searchError.value=e.message;}}
 finally{if(current()&&g===searchGeneration)searching.value=false;}
}
async function preview(id){
 const g=++previewGeneration;previewError.value='';previewLoading.value=true;
 try{const material=await prescription.material(id);if(!current()||g!==previewGeneration)return;revoke(previewUrl.value);previewUrl.value=URL.createObjectURL(new Blob([material.bytes],{type:material.mime}));ownedUrls.push(previewUrl.value);}
 catch(e){if(current()&&g===previewGeneration)previewError.value=e.message;}
 finally{if(current()&&g===previewGeneration)previewLoading.value=false;}
}
async function submit(){
 if(busy.value||!current())return;
 busy.value=true;actionError.value='';uncertain.value=false;
 try{
  const currentStore=await prescription.ensureStore();
  if(!current())return;
  if(!store.value||Number(store.value.id)!==Number(currentStore.id)){store.value=currentStore;for(const file of files.value)if(file.storeId&&file.storeId!==currentStore.id)file.id=null;throw Error('门店已变化，请确认材料后重新提交');}
  if(!form.value.patientName.trim()||!items.value.length||!files.value.length)throw Error('请填写患者姓名、药品和处方图片');
  const materialIds=[];
  for(const file of files.value){
   if(!file.id||file.storeId!==currentStore.id){if(!file.file)throw Error('处方图片需要重新选择后再上传');const uploaded=await prescription.upload(file.file,currentStore.id);if(!current())return;file.id=uploaded;file.storeId=currentStore.id;}
   materialIds.push(file.id);
  }
  const id=await prescription.submit({storeId:currentStore.id,patientName:form.value.patientName,hospital:form.value.hospital,doctorName:form.value.doctorName,materialIds,items:items.value.map(item=>({drugId:item.drugId,qty:item.qty}))});
  if(!current())return;
  emit('notice','申请已提交，等待药师审核');
  location.hash='rx-detail?id='+id;
 }catch(e){if(!current())return;uncertain.value=!!e.uncertain;actionError.value=e.uncertain?`${e.message}。提交结果暂时无法确认，请保持内容不变后重试。`:e.message;}
 finally{if(current())busy.value=false;}
}
function otherStore(row){return !!(store.value&&row?.storeId&&Number(row.storeId)!==Number(store.value.id));}
onMounted(()=>{const pending=prescription.submission?.();uncertain.value=!!pending?.uncertain;window.addEventListener('firstsun-auth-clear',onAuthClear);load();});
onUnmounted(()=>{disposed=true;generation++;searchGeneration++;previewGeneration++;revokeAll();window.removeEventListener('firstsun-auth-clear',onAuthClear);});
</script>
<template>
<main class="customer-page">
 <div class="breadcrumb"><a href="#catalog">找药购药</a><Icon name="chevron-right"/><span>{{route==='rx-detail'?'申请详情':route==='rx-submit'?'提交处方申请':'我的处方'}}</span></div>
 <div class="customer-title"><div><div class="eyebrow">FIRSTSUN / PRESCRIPTION</div><h1>{{route==='rx-detail'?'申请详情':route==='rx-submit'?'提交处方申请':'我的处方'}}</h1><p>私有材料仅限本人及有权限的本门店药师读取。提交不代表审核通过，核准明细以服务端返回为准。本页不办理结算或付款。</p></div><a v-if="route==='rx-submit'" class="btn" href="#prescriptions">查看我的申请</a><a v-else class="btn primary" href="#rx-submit">提交新申请</a></div>
 <div v-if="!user" class="empty-state"><Icon name="circle-user-round"/><h2>登录后办理处方申请</h2><p>处方材料和审核记录只对本人展示。</p><button class="btn primary" @click="emit('login')">登录会员账号</button></div>
 <template v-else>
  <div v-if="loadError" class="error-panel trade-error" role="alert">{{loadError}}<button class="btn link" :disabled="loading||busy" @click="load">重新读取</button></div>
  <div v-if="loading" class="skeleton" aria-busy="true" aria-label="正在读取处方"></div>
  <template v-else-if="route==='prescriptions'">
   <p v-if="store" class="small muted">新申请将提交到当前门店 {{store.name}}（#{{store.id}}）。列表包含本人在各门店的申请。</p>
   <section v-for="row in records" :key="row.id" class="paper order-card"><div class="paper-head"><span class="small muted presc-no">{{row.prescNo||'处方号未返回'}} · {{formatDate(row.createTime)||'时间未返回'}}</span><span class="state-tag" :class="{warm:Number(row.reviewStatus)===2||Number(row.status)===2}">{{reviewLabel(row)}}</span></div><div class="pad"><p>{{row.patientName||'患者未返回'}} · {{row.storeId?`门店 #${row.storeId}`:'门店未返回'}}<span v-if="otherStore(row)"> · 非当前门店</span></p><p class="small muted">{{recordState(row)}}</p><a class="btn" :href="'#rx-detail?id='+row.id">查看申请与审核结果</a></div></section>
   <div v-if="!records.length&&!loadError" class="empty-state"><Icon name="file-check-2"/><h2>暂无处方申请</h2><p>可以上传真实处方材料，交由药师审核。</p><a class="btn primary" href="#rx-submit">提交新申请</a></div>
  </template>
  <template v-else-if="route==='rx-detail'&&record">
   <section class="paper"><div class="paper-head"><div><h2 class="presc-no">{{record.prescNo||'处方号未返回'}}</h2><p class="small muted">{{formatDate(record.createTime)||'时间未返回'}} · {{record.storeId?`门店 #${record.storeId}`:'门店未返回'}}<span v-if="otherStore(record)"> · 非当前门店</span></p></div><span class="state-tag" :class="{warm:Number(record.reviewStatus)===2||Number(record.status)===2}">{{reviewLabel(record)}}</span></div><div class="pad"><p>记录状态：{{recordState(record)}}</p><p v-if="Number(record.status)===2||Number(record.reviewStatus)===0">{{reviewNote(record)}}</p><p v-else-if="Number(record.reviewStatus)===1&&reviewNote(record)">药师意见：{{reviewNote(record)}}</p><section v-else-if="Number(record.reviewStatus)===2"><h3>驳回原因</h3><p>{{rejectionReason(record)}}</p><a class="btn" :href="'#rx-submit?from='+record.id">修改后重新提交新申请</a></section><p v-else-if="Number(record.reviewStatus)!==1">审核状态暂无法确认，请稍后刷新。不要把申请内容当作审核结果。</p><dl class="details-list"><dt>患者</dt><dd>{{record.patientName||'未返回'}}</dd><dt>医院</dt><dd>{{record.hospital||'未填写'}}</dd><dt>医师</dt><dd>{{record.doctorName||'未填写'}}</dd></dl></div></section>
   <section class="paper pad"><h2>申请明细</h2><p class="small muted">以下是提交时的药品和数量，不是药师核准结果。</p><div v-if="record.requestedItems.length"><div v-for="item in record.requestedItems" :key="'request-'+item.drugId" class="rx-item"><div><strong>{{item.drugName}}</strong><p class="small muted">{{item.specification||'规格未返回'}} · 药品 #{{item.drugId||'未返回'}}</p></div><span>× {{item.qty??'未返回'}}</span></div></div><p v-else>服务端未返回申请明细。</p></section>
   <section v-if="record.approvedItems.length||(Number(record.reviewStatus)===1&&Number(record.status)!==2)" class="paper pad"><h2>核准明细</h2><p v-if="Number(record.status)===2">记录已作废，下列内容不能作为购买依据。</p><p v-if="record.approvedItems.length" class="small muted">数量、用法、用量和有效期均来自服务端，本页不补写。</p><p v-else>审核状态为通过，但服务端未返回核准明细。本页不会根据申请内容补写。</p><div v-for="item in record.approvedItems" :key="'approved-'+item.drugId" class="rx-item"><div><strong>{{item.drugName}} × {{item.qty??'未返回'}}</strong><p class="small muted">{{item.specification||'规格未返回'}} · 用法 {{item.usage||'未返回'}} · 用量 {{item.dosage||'未返回'}}</p></div></div><p class="small muted">核准有效期：{{formatDate(record.approvedUntil)||'服务端未返回'}}</p><p>购买与付款不在本页办理。</p></section>
   <section class="paper pad"><h2>私有材料</h2><p v-if="!record.materialIds.length">服务端未返回可查看的私有材料。</p><div v-else class="rx-actions"><button v-for="id in record.materialIds" :key="id" class="btn" :disabled="previewLoading" @click="preview(id)">查看材料 {{id}}</button></div><p v-if="previewLoading" role="status">正在读取私有材料…</p><p v-if="previewError" class="error-panel" role="alert">{{previewError}}</p><img v-if="previewUrl" class="rx-preview" :src="previewUrl" alt="处方材料预览，仅本人可见"></section>
   <section v-if="record.uses.length" class="paper pad"><h2>使用记录</h2><div v-for="use in record.uses" :key="(use.wxOrderId||'order')+'-'+(use.createTime||'')" class="rx-item"><div><strong>{{useLabel(use.status)}}</strong><p class="small muted">{{formatDate(use.createTime)||'时间未返回'}}<span v-if="use.releaseReason"> · {{use.releaseReason}}</span></p></div><a v-if="Number.isSafeInteger(use.wxOrderId)&&use.wxOrderId>0" class="btn" :href="'#order-detail?id='+use.wxOrderId">查看订单 {{use.wxOrderId}}</a></div><p class="small muted">这里只展示服务端已有记录，不能在本页付款或再次结算。</p></section>
   <div class="page-actions"><button class="btn" :disabled="loading||busy" @click="load"><Icon name="refresh-cw"/>刷新审核状态</button><a class="btn" href="#prescriptions">返回申请列表</a></div>
  </template>
  <div v-else-if="route==='rx-detail'&&!loadError" class="empty-state"><h2>没有读到这份申请</h2><a class="btn" href="#prescriptions">返回申请列表</a></div>
  <template v-else-if="route==='rx-submit'&&store">
   <div v-if="actionError" class="error-panel trade-error" role="alert">{{actionError}}</div>
   <p v-if="notice" class="notice-panel" role="status">{{notice}}</p>
   <div class="rx-layout">
    <div class="stack">
     <section class="paper pad"><h2>处方信息</h2><p class="small muted">当前门店 {{store.name}}（#{{store.id}}）。材料会绑定这个门店。</p><label class="form-field">患者姓名<input v-model="form.patientName" maxlength="32" :disabled="busy" autocomplete="name" placeholder="填写实际患者姓名"></label><label class="form-field">开具医院<input v-model="form.hospital" maxlength="100" :disabled="busy" placeholder="按处方填写，可留空"></label><label class="form-field">医师姓名<input v-model="form.doctorName" maxlength="32" :disabled="busy" placeholder="按处方填写，可留空"></label></section>
     <section class="paper pad"><h2>申请药品与数量</h2><p class="small muted">最多 20 项，每种 1–999。用法用量由药师审核后返回，这里不能代填。</p><div class="rx-search"><input v-model="keyword" :disabled="busy" placeholder="搜索药品名称" aria-label="搜索药品名称" @keydown.enter.prevent="search"><button class="btn" :disabled="busy||searching||!keyword.trim()" @click="search">搜索</button></div><p v-if="searching" role="status">正在搜索当前门店药品…</p><p v-if="searchError" class="small" role="status">{{searchError}}</p><button v-for="product in results" :key="product.id" class="btn link rx-result" :disabled="busy" @click="selectDrug(product)">{{product.name}} · {{product.specification||'规格未返回'}} · {{product.rx?'处方药':'商品'}}</button><div v-for="item in items" :key="item.drugId" class="rx-item"><div><strong>{{item.name}}</strong><p class="small muted">{{item.specification||'规格未返回'}}</p></div><input class="rx-qty" type="number" min="1" max="999" step="1" :value="item.qty" :disabled="busy" :aria-label="item.name+'数量'" @change="setQty(item,$event)"><button class="btn" :disabled="busy" @click="removeItem(item.drugId)">移除</button></div></section>
     <section class="paper pad"><h2>处方材料</h2><p class="small muted">1–3 张 PNG 或 JPEG，单张不超过 5MB。上传失败不会显示为已提交或审核通过。</p><div class="rx-files"><div v-for="(file,index) in files" :key="file.key" class="rx-file"><img :src="file.localUrl" alt="已选择的处方图片"><div class="grow"><strong>{{file.name}}</strong><p class="small muted">{{file.id?'已私有上传，尚未提交申请':'待上传'}}</p></div><button class="btn" :disabled="busy" @click="removeFile(index)">移除</button></div></div><label class="btn">选择处方图片<input class="rx-file-input" type="file" accept="image/png,image/jpeg,.png,.jpg,.jpeg" multiple :disabled="busy||files.length>=3" aria-label="选择处方图片" @change="chooseFiles"></label></section>
    </div>
    <aside class="summary-box"><h2>提交前核对</h2><dl><dt>门店</dt><dd>#{{store.id}}</dd><dt>药品</dt><dd>{{items.length}} 项</dd><dt>材料</dt><dd>{{files.length}} 张</dd></dl><p>服务端会再次校验门店、药品和材料归属。本页不生成审核结论。</p><button class="btn primary full" :disabled="busy||!form.patientName.trim()||!items.length||!files.length" @click="submit">{{busy?'提交中…':uncertain?'重试同一申请':'提交给药师审核'}}</button></aside>
   </div>
  </template>
 </template>
 <p class="customer-foot">审核状态、驳回原因和核准明细以服务端为准</p>
</main>
</template>
<style scoped>
.presc-no{overflow-wrap:anywhere}
.rx-layout{display:grid;grid-template-columns:minmax(0,1fr) 300px;gap:22px;align-items:start}
.rx-search{display:flex;gap:10px;margin-top:16px}
.rx-search input{flex:1;min-width:0;height:43px;border:1px solid #dbe4de;border-radius:5px;padding:10px}
.rx-result{display:block;text-align:left;margin-top:8px}
.rx-item{display:flex;gap:12px;align-items:center;justify-content:space-between;padding:12px 0;border-bottom:1px solid var(--line)}
.rx-item>div{min-width:0}
.rx-qty{width:88px;height:40px;border:1px solid #dbe4de;border-radius:5px;padding:8px}
.rx-files{display:flex;flex-direction:column;gap:12px;margin:12px 0}
.rx-file{display:flex;gap:12px;align-items:center}
.rx-file img,.rx-preview{width:72px;height:72px;object-fit:contain;background:var(--surface);border:1px solid var(--line);border-radius:6px}
.rx-preview{width:min(100%,480px);height:auto;max-height:420px;margin-top:12px}
.rx-actions{display:flex;flex-wrap:wrap;gap:8px}
.rx-file-input{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0)}
.pad h3{font-size:16px;margin:8px 0}
@media(max-width:700px){.rx-layout{display:block}.rx-layout .summary-box{margin-top:20px}.rx-item{align-items:flex-start;flex-wrap:wrap}}
</style>

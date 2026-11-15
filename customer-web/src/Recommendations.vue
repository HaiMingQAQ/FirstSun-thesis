<script setup>
import {computed,onMounted,onUnmounted,ref} from 'vue';
import catalog from '@/sheep/api/pharmacy/server';
import DrugApi from '@/sheep/api/pharmacy/drug';
import InventoryApi from '@/sheep/api/pharmacy/inventory';
import {money,normalizeDrug} from '@/sheep/api/pharmacy/common';
import {safeImage} from './guards.js';

const props=defineProps({featured:Boolean});
const pageSize=100;
const featuredLimit=4;
const products=ref([]),page=ref(0),total=ref(0),loading=ref(false),error=ref('');
const failedImages=ref(new Set());
const hasImage=product=>!!product.image&&!failedImages.value.has(product.id);
const pictured=computed(()=>products.value.filter(hasImage));
const cards=computed(()=>{
 const ordered=[...pictured.value,...products.value.filter(product=>!hasImage(product))];
 return props.featured?ordered.slice(0,featuredLimit):ordered;
});
const hasMore=computed(()=>page.value*pageSize<total.value);
let generation=0,storeId=null;

async function readPage(){
 const next=page.value+1;
 const response=await DrugApi.getDrugPage({pageNo:next,pageSize});
 if(response?.code!==0)throw Error(response?.msg||'商品暂时无法读取');
 const {list,total:count}=response.data||{};
 if(!Array.isArray(list)||!Number.isSafeInteger(Number(count))||Number(count)<0)throw Error('商品列表返回异常');
 const ids=new Set(products.value.map(product=>product.id));
 const additions=[];
 for(const row of list){
  if(!row||!Number.isSafeInteger(row.id)||row.id<=0||Number(row.isRx)===1||ids.has(row.id))continue;
  const product=normalizeDrug(row,{image:row.imageUrl});
  const image=safeImage(product.image);
  ids.add(product.id);
  additions.push({...product,image,label:row.categoryName||(product.device?'医疗器械':'商品档案'),
   price:row.memberPrice==null&&row.retailPrice==null?null:product.price});
 }
 return {additions,next,count:Number(count)};
}

async function inStock(products,storeId){
 if(!products.length)return [];
 const response=await InventoryApi.getAvailable({storeId,drugIds:products.map(product=>product.id).join(',')});
 if(response?.code!==0||!Array.isArray(response.data))throw Error('当前门店库存暂时无法读取');
 const available=new Set(response.data.filter(row=>Number(row?.qtyAvail)>0).map(row=>Number(row.drugId)));
 return products.filter(product=>available.has(product.id));
}

async function load(){
 if(loading.value)return;
 const request=++generation;
 const current=()=>request===generation;
 loading.value=true;
 error.value='';
 try{
  if(props.featured){
   const store=catalog.store.id?catalog.store:await catalog.refreshStore();
   if(!current())return;
   storeId=store.id;
  }
  // The adapter clears its store while refreshing. A resolved store change restarts this load.
  const sameStore=()=>!props.featured||!catalog.store.id||storeId===catalog.store.id;
  do{
   const {additions,next,count}=await readPage();
   if(!current()||!sameStore())return;
   const nextProducts=props.featured?await inStock(additions,storeId):additions;
   if(!current()||!sameStore())return;
   products.value=[...products.value,...nextProducts];
   page.value=next;
   total.value=count;
  }while(props.featured&&pictured.value.length<featuredLimit&&hasMore.value);
 }catch(e){if(current())error.value=e.message||'商品暂时无法读取';}
 finally{if(current())loading.value=false;}
}

function imageFailed(product){
 if(!products.value.includes(product))return;
 failedImages.value.add(product.id);
 if(props.featured&&pictured.value.length<featuredLimit&&hasMore.value&&!error.value)load();
}

function storeChanged(){
 if(!storeId&&loading.value)return;
 if(storeId===catalog.store.id)return;
 generation++;
 storeId=null;
 products.value=[];
 failedImages.value.clear();
 page.value=0;
 total.value=0;
 loading.value=false;
 load();
}

onMounted(()=>{
 if(props.featured)window.addEventListener('firstsun-store-change',storeChanged);
 load();
});
onUnmounted(()=>{
 generation++;
 if(props.featured)window.removeEventListener('firstsun-store-change',storeChanged);
});
</script>

<template>
 <component :is="featured?'section':'main'" class="recommend-page" :class="{featured}" aria-labelledby="recommend-title">
  <header class="recommend-heading">
   <div>
    <p class="recommend-kicker">EVERYDAY ESSENTIALS</p>
    <component :is="featured?'h2':'h1'" id="recommend-title">日常所需，用心备好。</component>
    <p>{{featured?'当前门店有货商品':'从居家常备到日常护理，照顾生活里的每一面。'}}</p>
   </div>
   <a class="recommend-link" :href="featured?'#recommend':'#catalog'">{{featured?'查看全部推荐':'找药购药'}} <Icon name="arrow-right"/></a>
  </header>
  <div v-if="loading" class="recommend-state" role="status">{{featured?'正在读取门店商品与库存…':'正在读取商品…'}}</div>
  <div v-if="error" class="error-panel" role="alert">{{error}} <button type="button" class="btn link" @click="load">重试</button></div>
  <div v-if="cards.length" class="recommend-grid">
   <a v-for="p in cards" :key="p.id" class="recommend-card" :data-recommend-id="p.id" :href="'#detail?id='+p.id" :aria-label="'查看'+(p.genericName||p.name)+'详情'">
    <div class="recommend-photo">
     <img v-if="hasImage(p)" :src="p.image" :alt="(p.genericName||p.name)+'商品图'" width="300" height="300" @error="imageFailed(p)">
     <div v-else class="product-photo missing"><Icon name="image"/><span>商品图片暂未提供</span></div>
     <span class="recommend-kind">{{p.label}}</span>
    </div>
    <div class="recommend-info">
     <component :is="featured?'h3':'h2'">{{p.genericName||p.name}}</component>
     <p>{{p.specification||'规格暂未提供'}}</p>
     <div class="recommend-bottom">
      <span v-if="Number.isFinite(p.price)" class="recommend-price"><small>¥</small>{{money(p.price)}}</span>
      <span v-else class="recommend-unpriced">价格暂未提供</span>
      <span class="recommend-arrow"><Icon name="arrow-right"/></span>
     </div>
    </div>
   </a>
  </div>
  <div v-else-if="!loading&&!error" class="empty-state"><Icon name="package-open"/><component :is="featured?'h3':'h2'">{{featured?'当前门店暂无可展示的有货商品':'暂无可推荐商品'}}</component><a class="btn" href="#catalog">浏览全部商品</a></div>
  <p v-if="cards.length" class="recommend-note">商品图片仅供参考，请以实物为准。</p>
  <button v-if="!featured&&hasMore&&!error" type="button" class="btn recommend-more" :disabled="loading" @click="load">加载更多</button>
 </component>
</template>

<style scoped>
.recommend-page{width:min(1280px,calc(100% - 96px));margin:0 auto;padding:48px 0 64px}
.recommend-page.featured{width:100%;padding:66px 0 0}
.recommend-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;margin-bottom:28px}
.recommend-kicker{color:#0e5e3f;font-size:11px;letter-spacing:2.4px;margin:0 0 12px}
.recommend-heading :is(h1,h2){margin:0;color:#243c34;font-size:32px;line-height:1.3;font-weight:600;letter-spacing:-1px}
.recommend-heading :is(h1,h2)+p{margin:12px 0 0;color:#52685b;font-size:13px;line-height:1.7}
.recommend-link{display:inline-flex;align-items:center;gap:8px;color:#0e5e3f;white-space:nowrap;min-height:44px;font-size:13px}
.recommend-link:hover{text-decoration:underline;text-underline-offset:4px}
.recommend-link svg{width:17px;height:17px}
.recommend-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:20px}
.recommend-card{display:flex;flex-direction:column;min-width:0;overflow:hidden;background:white;border:1px solid #e3e8e1;border-radius:15px;transition:box-shadow .2s,border-color .2s}
a.recommend-card:hover{border-color:#a9c6b4;box-shadow:0 10px 28px #153f2a10}
.recommend-card:focus-visible,.recommend-link:focus-visible{outline:2px solid #a85d26;outline-offset:4px}
.recommend-photo{position:relative;aspect-ratio:1;display:grid;place-items:center;background:#f0f2ed;overflow:hidden}
.recommend-photo>.product-photo.missing{height:100%;border:0}
.recommend-photo img{width:100%;height:100%;object-fit:contain;min-width:0}
.recommend-kind{position:absolute;top:14px;left:14px;max-width:calc(100% - 28px);padding:4px 8px;background:white;border:1px solid #e2e9e0;border-radius:4px;color:#0e5e3f;font-size:10px;overflow-wrap:anywhere}
.recommend-info{display:flex;flex-direction:column;flex:1;padding:18px 20px 19px}
.recommend-info :is(h2,h3){margin:0;color:#243c34;font-size:16px;font-weight:600;overflow-wrap:anywhere}
.recommend-info p{margin:8px 0 16px;color:#52685b;font-size:12px;line-height:1.6;overflow-wrap:anywhere}
.recommend-bottom{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-top:auto}
.recommend-price{color:#0a4630;font:25px Georgia,serif;white-space:nowrap}
.recommend-price small{margin-right:3px;font:12px "Microsoft YaHei",sans-serif}
.recommend-unpriced{font-size:12px;color:#52685b}
.recommend-arrow{width:37px;height:37px;display:grid;place-items:center;flex:none;border:1px solid #dce6df;border-radius:50%;color:#0e5e3f}
.recommend-arrow svg{width:17px;height:17px}
.recommend-card:hover .recommend-arrow{background:#0e5e3f;color:white}
.recommend-state{color:#52685b;font-size:13px;padding:12px 0}
.recommend-note{margin:18px 0 0;color:#52685b;font-size:12px}
.recommend-more{display:block;margin:24px auto 0}
.recommend-page .error-panel{margin-bottom:16px}
@media(min-width:1600px){.recommend-page{width:min(1420px,calc(100% - 120px))}}
@media(max-width:1100px){.recommend-page{width:calc(100% - 48px)}.recommend-grid{gap:14px}.recommend-info{padding-inline:14px}}
@media(max-width:760px){
 .recommend-page{width:calc(100% - 32px);padding-top:28px}
 .recommend-heading{align-items:flex-start;flex-direction:column;gap:8px;margin-bottom:16px}
 .recommend-page.featured{padding-top:43px}
 .recommend-heading :is(h1,h2){font-size:26px}.recommend-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
 .recommend-info{padding:14px 12px}.recommend-info :is(h2,h3){font-size:14px}.recommend-info p{font-size:11px}
 .recommend-kind{left:8px;top:8px;max-width:calc(100% - 16px)}.recommend-price{font-size:21px}
 .recommend-arrow{width:30px;height:30px}
}
@media(prefers-reduced-motion:reduce){.recommend-card{transition:none}}
</style>

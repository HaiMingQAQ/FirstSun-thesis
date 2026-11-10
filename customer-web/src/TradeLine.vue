<script setup>
import {computed,ref,watch} from 'vue';
import DrugApi from '@/sheep/api/pharmacy/drug';
import {normalizeDrug} from '@/sheep/api/pharmacy/common';
import {safeImage} from './guards.js';
import {yuanMoney,estimate} from './trade-core.js';
const props=defineProps({row:{type:Object,required:true},editable:Boolean,showCatalogInfo:Boolean,showCatalogImage:Boolean,busy:Boolean,stock:[Number,String]});
const emit=defineEmits(['quantity','select','remove']);
const product=ref(null),imageFailed=ref(false);
const name=computed(()=>(props.showCatalogInfo&&product.value?.name)||props.row.drugName||'商品名称未提供');
const genericName=computed(()=>props.showCatalogInfo&&product.value?.genericName!==name.value?product.value?.genericName:'');
const image=computed(()=>!imageFailed.value&&product.value?.image?safeImage(product.value.image):'');
watch([()=>props.showCatalogInfo||props.showCatalogImage,()=>props.row.drugId,()=>Number(props.row.isRx)===1],async ([enabled,id,rx],_,onCleanup)=>{
 let current=true;
 onCleanup(()=>{current=false;});
 product.value=null;
 imageFailed.value=false;
 if(!enabled||!id||rx)return;
 try{
  const result=await DrugApi.getDrug(id);
  const raw=result?.data;
  if(current&&result?.code===0&&raw&&String(raw.id)===String(id)&&raw.isRx!=null&&Number(raw.isRx)===0){
   product.value=normalizeDrug(raw,{image:raw.imageUrl});
  }
 }catch{/* Keep the transaction record visible when the catalog is unavailable. */}
},{immediate:true});
function select(event){const selected=event.target.checked;event.target.checked=props.row.selectedFlag===1;emit('select',selected);}
</script>
<template><div class="purchase-item" :data-row-id="row.id" :data-drug-id="row.drugId" :data-store-id="row.storeId"><input v-if="editable" type="checkbox" :aria-label="'选择'+name" :checked="row.selectedFlag===1" :disabled="busy" @change="select"><a class="purchase-photo" :href="'#detail?id='+row.drugId"><div class="product-photo" :class="{missing:!image}"><img v-if="image" :src="image" :alt="name+'商品图'" :title="showCatalogImage?'当前商品图片，仅供参考':undefined" @error="imageFailed=true"><template v-else><Icon :name="row.isRx===1?'lock-keyhole':'package'"/><span>{{row.isRx===1?'处方药':'商品记录'}}</span></template></div></a><div class="purchase-name"><span v-if="row.isRx===1" class="tag rx">Rx 处方药 · 不可普通结算</span><a :href="'#detail?id='+row.drugId"><h3>{{name}}</h3></a><p v-if="genericName">{{genericName}}</p><p>{{row.specification||'规格未提供'}}<span v-if="row.unit"> / {{row.unit}}</span></p><span v-if="stock!==undefined" class="small muted">当前可售量 {{stock}}</span></div><div class="item-unit"><span class="trade-price">¥{{yuanMoney(editable?(row.memberPrice??row.retailPrice):row.price)}}</span></div><div v-if="editable" class="quantity"><button :disabled="busy||row.qty<=1" :aria-label="'减少'+name+'数量'" @click="emit('quantity',row.qty-1)"><Icon name="minus"/></button><output>{{row.qty}}</output><button :disabled="busy" :aria-label="'增加'+name+'数量'" @click="emit('quantity',row.qty+1)"><Icon name="plus"/></button></div><span v-else class="small muted">× {{row.qty}}</span><strong class="item-subtotal">¥{{yuanMoney(editable?estimate([row]):row.lineAmount)}}</strong><button v-if="editable" class="icon-btn" :disabled="busy" :aria-label="'移除'+name" @click="emit('remove')"><Icon name="trash-2"/></button></div></template>

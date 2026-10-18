<script setup>
import {computed,ref,watch} from 'vue';
import {money} from '@/sheep/api/pharmacy/common';
import {safeImage} from './guards.js';
const props=defineProps({product:{type:Object,required:true},imageOnly:Boolean,stockUnknown:Boolean});
defineEmits(['unavailable']);const failed=ref(false);watch(()=>props.product.image,()=>failed.value=false);
const image=computed(()=>!props.product.rx&&!failed.value?safeImage(props.product.image):'');
</script>
<template><div v-if="imageOnly" class="product-photo" :class="{missing:!image}"><img v-if="image" :src="image" :alt="product.name+'商品图'" @error="failed=true"><template v-else><Icon :name="product.rx?'lock-keyhole':'image'"/><span>{{product.rx?'处方资料受限':'商品图片暂未提供'}}</span></template></div><article v-else class="product-card" :data-product="product.id"><a class="product-link" :href="'#detail?id='+product.id" :aria-label="'查看'+product.name+'详情'"><div class="product-photo" :class="{missing:!image}"><img v-if="image" :src="image" :alt="product.name+'商品图'" @error="failed=true"><template v-else><Icon :name="product.rx?'lock-keyhole':'image'"/><span>{{product.rx?'处方资料受限':'商品图片暂未提供'}}</span></template></div><div class="product-body"><span class="tag" :class="{rx:product.rx}">{{product.rx?'Rx 处方药':product.device?'医疗器械':'OTC 非处方'}}</span><h3>{{product.name}}</h3><p>{{product.specification||'规格暂未提供'}}</p></div></a><div class="product-bottom"><div><span class="price"><small>¥</small>{{money(product.price)}}</span><span class="stock">{{stockUnknown?'库存暂不可用':product.stock>0?`有货 · ${product.stock} 件`:'暂无可售库存'}}</span></div><a class="add-btn" :href="'#detail?id='+product.id" :aria-label="'查看'+product.name+'详情'"><Icon name="arrow-up-right"/></a></div></article></template>

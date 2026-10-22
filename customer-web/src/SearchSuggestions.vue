<script setup>
import {ref,watch,onUnmounted} from 'vue';
import api from '@/sheep/api/pharmacy/server';
import {money} from '@/sheep/api/pharmacy/common';
import {identity} from './session.js';
const props=defineProps({modelValue:String,category:[String,Number],storeId:[String,Number],loading:Boolean});
const emit=defineEmits(['update:modelValue','search','select']);
const rows=ref([]),open=ref(false),pending=ref(false),error=ref(''),active=ref(-1);
let timer,generation=0,disposed=false,focused=false,resultOwner='',resultStore=null;
const owner=()=>JSON.stringify(identity());
function close(){generation++;clearTimeout(timer);rows.value=[];open.value=false;pending.value=false;error.value='';active.value=-1;}
function schedule(){
 close();const keyword=(props.modelValue||'').trim();if(!keyword||!props.storeId)return;
 const g=generation,who=owner(),store=props.storeId,category=props.category;
 open.value=true;pending.value=true;
 const current=()=>!disposed&&g===generation&&who===owner()&&keyword===(props.modelValue||'').trim()&&category===props.category&&store===props.storeId&&String(store)===String(api.store.id);
 timer=setTimeout(async()=>{if(!current()){if(g===generation)close();return;}try{const page=await api.productPage({keyword,category,pageNo:1,pageSize:10});if(!current())return;rows.value=page.list.slice(0,10);resultOwner=who;resultStore=store;}catch(e){if(current())error.value=e.message||'搜索建议暂不可用';}finally{if(current())pending.value=false;else if(g===generation)close();}},200);
}
watch(()=>[props.modelValue,props.category,props.storeId],()=>focused?schedule():close(),{flush:'sync'});
function focus(){focused=true;schedule();}
function select(product){if(!open.value||!rows.value.includes(product)||resultOwner!==owner()||resultStore!==props.storeId||String(resultStore)!==String(api.store.id)){close();return;}close();emit('select',product.id);}
function keydown(event){if(event.key==='Escape'){event.preventDefault();close();}else if(['ArrowDown','ArrowUp'].includes(event.key)&&open.value&&rows.value.length){event.preventDefault();const n=rows.value.length;active.value=event.key==='ArrowDown'?(active.value+1)%n:(active.value<=0?n-1:active.value-1);}else if(event.key==='Enter'&&open.value&&active.value>=0){event.preventDefault();select(rows.value[active.value]);}}
function submit(){close();emit('search');}
function blur(event){if(!event.currentTarget.contains(event.relatedTarget)){focused=false;close();}}
onUnmounted(()=>{disposed=true;close();});
defineExpose({close});
</script>
<template><div class="search-autocomplete" @focusout="blur"><form class="search-box" @submit.prevent="submit"><Icon name="search"/><input :value="modelValue" role="combobox" aria-label="搜索药品" aria-autocomplete="list" aria-controls="drug-suggestions" :aria-expanded="open" :aria-activedescendant="active>=0?`drug-suggestion-${active}`:undefined" placeholder="输入药品名称" maxlength="100" @input="emit('update:modelValue',$event.target.value)" @focus="focus" @keydown="keydown"><button class="btn primary" :disabled="loading">搜索药品</button></form><div v-if="open" class="search-suggestions"><div class="suggestion-heading"><span>当前门店匹配商品 · 最多 10 件</span><button type="button" class="btn link" aria-label="关闭搜索建议" @click="close">关闭</button></div><p v-if="pending" role="status">正在查找匹配商品…</p><p v-else-if="error" role="alert">{{error}}</p><ul v-else-if="rows.length" id="drug-suggestions" role="listbox" aria-label="匹配商品"><li v-for="(p,i) in rows" :key="p.id" role="presentation"><button :id="`drug-suggestion-${i}`" type="button" role="option" tabindex="-1" :aria-selected="active===i" :class="{active:active===i}" @mousedown.prevent @click="select(p)"><span><strong>{{p.name}}</strong><span>{{p.specification||'规格未提供'}}</span></span><span>¥{{money(p.price)}}<span>可售 {{p.stock}}</span></span></button></li></ul><p v-else role="status">没有匹配商品，请调整关键词。</p></div></div></template>
<style scoped>
.search-autocomplete{position:relative}.search-suggestions{position:absolute;z-index:20;top:calc(100% + 6px);left:0;right:0;background:#fff;border:1px solid #dbe2eb;border-radius:12px;box-shadow:0 12px 32px #182b4820;max-height:420px;overflow:auto}.suggestion-heading{display:flex;align-items:center;justify-content:space-between;padding:8px 14px;font-size:12px;color:#5b6678}.search-suggestions p{padding:10px 16px}.search-suggestions ul{list-style:none;margin:0;padding:0}.search-suggestions li button{display:flex;justify-content:space-between;gap:16px;width:100%;padding:10px 16px;text-align:left;background:white;border:0;border-top:1px solid #edf0f4;cursor:pointer}.search-suggestions li button:hover,.search-suggestions li button.active{background:#edf5ff}.search-suggestions li span span{display:block;margin-top:4px;font-size:12px;color:#687386}.search-suggestions li button>span:last-child{text-align:right;white-space:nowrap}
</style>

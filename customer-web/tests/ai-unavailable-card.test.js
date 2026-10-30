import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as Vue from 'vue';
import {renderToString} from 'vue/server-renderer';
import {parse} from '@vue/compiler-sfc';
import {compile} from '@vue/compiler-dom';
import {money,normalizeDrug,formatDate} from '../../mall-uniapp/sheep/api/pharmacy/common.js';
import {safeImage} from '../src/guards.js';

// Exercise the real AI projection and card template without a browser or API.
const source=file=>fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8');
const app=source('App.vue');
function appFunction(name,context){
 const body=app.split(/\r?\n/).find(line=>line.startsWith('function '+name+'('));
 assert(body,'App.vue must expose its existing '+name+' function');
 return vm.runInNewContext('('+body+')',context);
}
const aiProducts=appFunction('aiProducts',{normalizeDrug});
const restored=appFunction('restored',{aiProducts,formatDate});
const {descriptor}=parse(source('ProductCard.vue'));
const render=new Function('Vue',compile(descriptor.template.content,{mode:'function',prefixIdentifiers:true}).code)(Vue);
const card={
 props:{product:Object,imageOnly:Boolean,stockUnknown:Boolean,adding:Boolean},
 components:{Icon:{render:()=>null}},
 setup(props){
  const context={ref:Vue.ref,computed:Vue.computed,watch:Vue.watch,defineProps:()=>props,defineEmits:()=>()=>{},money,safeImage};
  vm.runInNewContext(descriptor.scriptSetup.content.replace(/^import .*$/gm,'')+';globalThis.view={image,failed};',context);
  return{...Vue.toRefs(props),...context.view,money};
 },
 render,
};
const product=(id,qty)=>({id,name:'测试商品'+id,genericName:'测试通用名',specification:'测试规格',price:9,availableQty:qty});
const renderCard=p=>renderToString(Vue.createSSRApp(card,{product:p}));
const addButton=html=>{
 const button=[...html.matchAll(/<button\b[^>]*>/g)].map(match=>match[0]).find(tag=>tag.includes('加入购物车：'));
 assert(button,'ordinary OTC card must retain its add-to-cart button');
 return button;
};

test('AI cards preserve zero and positive quantities and disable only the unavailable card',async()=>{
 const rows=aiProducts([product(101,0),product(102,7)]);
 assert.equal(rows[0].stock,0);assert.equal(rows[1].stock,7);
 const unavailable=await renderCard(rows[0]),available=await renderCard(rows[1]);
 assert.match(unavailable,/暂无可售库存/);assert.match(addButton(unavailable),/\bdisabled(?:\s|=|>)/);
 assert.match(available,/有货 · 7 件/);assert.doesNotMatch(addButton(available),/\bdisabled(?:\s|=|>)/);
});

test('reloading an AI history turn retains the zero-stock card and its disabled purchase control',async()=>{
 const turn=restored({id:1,clientMessageId:'offline-history',content:'查看相关商品',response:{status:'SUCCESS',answer:'仅供查看资料',products:[product(101,0)]}});
 assert.equal(turn.products.length,1);assert.equal(turn.products[0].stock,0);assert.equal(turn.status,'SUCCESS');
 const html=await renderCard(turn.products[0]);
 assert.match(html,/暂无可售库存/);assert.match(addButton(html),/\bdisabled(?:\s|=|>)/);
});

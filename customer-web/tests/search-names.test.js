import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import * as Vue from 'vue';
import {renderToString} from 'vue/server-renderer';
import {parse} from '@vue/compiler-sfc';
import {compile} from '@vue/compiler-dom';

async function render(file,product){
 const {descriptor}=parse(fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8'));
 const render=new Function('Vue',compile(descriptor.template.content,{mode:'function',prefixIdentifiers:true}).code)(Vue);
 const state={product,rows:[product],open:true,pending:false,error:'',active:-1,modelValue:'',loading:false,imageOnly:false,image:'',stockUnknown:false,cartQty:0,adding:false,money:()=> '16.90',submit(){},blur(){},focus(){},keydown(){},close(){},select(){}};
 return renderToString(Vue.createSSRApp({setup:()=>state,render,components:{Icon:{render:()=>null}}}));
}

for(const [file,heading,secondary] of [
 ['SearchSuggestions.vue','strong','suggestion-trade-name'],
 ['ProductCard.vue','h3','product-trade-name'],
]){
 test(file+' displays generic name first and the distinct trade name second',async()=>{
  const html=await render(file,{id:163188,name:'华纳比乐',genericName:'蒙脱石散',specification:'3g/袋×10袋/盒',price:1690,stock:24});
  assert(html.includes('<'+heading+'>蒙脱石散</'+heading+'>'));
  assert.match(html,new RegExp('class="'+secondary+'">商品名 华纳比乐</'));
  assert(html.includes('3g/袋×10袋/盒'));
 });
 test(file+' omits duplicate secondary names',async()=>{
  const html=await render(file,{id:1,name:'同名商品',genericName:'同名商品',price:100,stock:1});
  assert(html.includes('<'+heading+'>同名商品</'+heading+'>'));
  assert(!html.includes('class="'+secondary+'"'));
 });
 test(file+' retains a legacy name when generic name is absent',async()=>{
  const html=await render(file,{id:1,name:'历史名称',price:100,stock:1});
  assert(html.includes('<'+heading+'>历史名称</'+heading+'>'));
  assert(!html.includes('class="'+secondary+'"'));
 });
}

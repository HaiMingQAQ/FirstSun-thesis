import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import * as Vue from 'vue';
import {renderToString} from 'vue/server-renderer';
import {parse} from '@vue/compiler-sfc';
import {compile} from '@vue/compiler-dom';

// Render the real templates on the server: no browser, DOM, API, or credentials.
async function render(file,extra={}){
 const {descriptor}=parse(fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8'));
 const render=new Function('Vue',compile(descriptor.template.content,{mode:'function'}).code)(Vue);
 const state={email:'member@example.test',code:'123456',password:'',confirm:'',busy:false,sending:false,sentEmail:'member@example.test',cooldown:0,error:'',mode:'code',props:{disabled:false},sent:true,validEmail:()=>true,submit(){},sendCode(){},changeMode(){},...extra};
 return renderToString(Vue.createSSRApp({setup:()=>state,render}));
}
const inputs=html=>[...html.matchAll(/<input\b[^>]*>/g)].map(match=>match[0]);

test('registration confirmation is visible from the start and required only with a password',async()=>{
 const empty=await render('EmailRegister.vue'),filled=await render('EmailRegister.vue',{password:'new12345'});
 assert.equal(inputs(empty).length,4);assert.equal(inputs(filled).length,4);
 assert.match(empty,/确认密码/);assert.equal(/\brequired(?:\s|=|>)/.test(inputs(empty)[3]),false);
 assert.match(inputs(filled)[3],/\brequired(?:\s|=|>)/);
});

for(const file of ['EmailRegister.vue','EmailLogin.vue','EmailFirstPassword.vue'])test(file+' displays its numeric one-time code as plain text',async()=>{
 const code=inputs(await render(file)).find(input=>input.includes('autocomplete="one-time-code"'));
 assert(code);assert.match(code,/type="text"/);assert.match(code,/inputmode="numeric"/);assert.match(code,/maxlength="6"/);
});

test('registration, login and first-password fields remain masked',async()=>{
 const register=inputs(await render('EmailRegister.vue'));
 assert.match(register[2],/type="password"/);assert.match(register[3],/type="password"/);
 const login=inputs(await render('EmailLogin.vue',{mode:'password'}));
 assert.match(login[1],/type="password"/);
 const first=inputs(await render('EmailFirstPassword.vue'));
 assert.match(first[1],/type="password"/);assert.match(first[2],/type="password"/);
});

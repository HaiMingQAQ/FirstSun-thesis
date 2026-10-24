import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as Vue from 'vue';
import {renderToString} from 'vue/server-renderer';
import {parse} from '@vue/compiler-sfc';
import {compile} from '@vue/compiler-dom';
const old='http://127.0.0.1:58090/admin-api/infra/file/45000044/get/member-profile-avatar/20261024/01234567-89ab-4cde-8fab-0123456789ab.png',current=old.replace(':58090',':18481');
const source=file=>fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8');
const empty={render:()=>null};
const renderer=template=>new Function('Vue',compile(template,{mode:'function',prefixIdentifiers:true}).code)(Vue);
async function memberAvatar(){
 if(!fs.existsSync(new URL('../src/MemberAvatar.vue',import.meta.url)))return empty;
 const {avatarUrl}=await import('../src/avatar-url.js'),{descriptor}=parse(source('MemberAvatar.vue'));
 return{props:{src:String,alt:{type:String,default:'会员头像'},size:{type:Number,default:28}},components:{Icon:empty},setup(props){const context={ref:Vue.ref,computed:Vue.computed,watch:Vue.watch,defineProps:()=>props,avatarUrl,localMediaOrigin:'http://127.0.0.1:18481'};vm.runInNewContext(descriptor.scriptSetup.content.replace(/^import .*$/gm,'')+';globalThis.view={props,imageUrl,failedUrl,onError};',context);return context.view;},render:renderer(descriptor.template.content)};
}
test('personal center renders a stored legacy avatar through the current local file origin',async()=>{
 const {descriptor}=parse(source('AccountPage.vue')),state={user:{},profile:{avatar:old,passwordSet:true},loading:false,busy:false,passwordBusy:false,nickname:'member',email:'',error:'',notice:'',emit(){},save(){},upload(){},accept(){}};
 const html=await renderToString(Vue.createSSRApp({components:{MemberAvatar:await memberAvatar(),Icon:empty,EmailFirstPassword:empty,PasswordGate:empty},setup:()=>state,render:renderer(descriptor.template.content)}));assert(html.includes('src="'+current+'"'));assert(!html.includes('src="'+old+'"'));assert.match(html,/width:76px;height:76px/);assert.match(html,/alt="会员头像"/);
});
test('the signed-in header renders the same corrected avatar',async()=>{
 const text=source('App.vue'),start=text.indexOf('<div class="header-tools">'),end=text.indexOf('</div></header>',start),state={user:{avatar:old},authName:'member',localTestLogin:false,authBusy:false,openLocalLogin(){},notConnected(){},go(){},loginOpen:false};
 const html=await renderToString(Vue.createSSRApp({components:{MemberAvatar:await memberAvatar(),Icon:empty},setup:()=>state,render:renderer(text.slice(start,end+6))}));assert(html.includes('src="'+current+'"'));assert(!html.includes('src="'+old+'"'));assert.match(html,/width:28px;height:28px/);assert.match(html,/\salt(?:=""|(?=>|\s))/);
});
test('load errors show a default avatar and A to B to A retries instead of retaining the failure',async()=>{
 const avatar=await memberAvatar(),props=Vue.reactive({src:old,size:76,alt:'会员头像'}),scope=Vue.effectScope(),view=scope.run(()=>avatar.setup(props));
 const render=()=>renderToString(Vue.createSSRApp({setup:()=>view,components:avatar.components,render:avatar.render}));
 view.onError({currentTarget:{getAttribute:()=>current}});assert.match(await render(),/aria-label="默认头像"/);assert(!(await render()).includes('<img'));
 props.src=old.replace('01234567-89ab','11234567-89ab');assert((await render()).includes('<img'));view.onError({currentTarget:{getAttribute:()=>current}});assert((await render()).includes('<img'));
 props.src=old;assert.equal(view.failedUrl.value,'');assert((await render()).includes('src="'+current+'"'));props.src='';assert.match(await render(),/aria-label="默认头像"/);scope.stop();
});

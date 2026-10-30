import {identity,clearLoginState,setTokens} from './session.js';
import {sameIdentity} from './guards.js';
export {clearLoginState};
// Port of sheep/request's envelope, token, tenant, refresh and auth invalidation contract.
// Account-scoped adapter calls explicitly disable refresh, preserving their captured-owner guards.
let refreshing;
export default async function request(config){
 const snapshot=identity(),custom={...(config.custom||{})},headers={'Content-Type':'application/json',Accept:'*/*',terminal:'20',platform:'H5'};
 // 购物车、订单、地址和处方都绑定当前登录身份，不在迟到的 401 上自动刷新令牌。
 if(/^\/member\/(wx-cart|wx-order|address|prescription)\//.test(config.url))custom.skipRefresh=true;
 if(!custom.skipTenant)headers['tenant-id']=snapshot.tenant;
 if(custom.isToken!==false&&snapshot.token)headers.Authorization=snapshot.token;
 Object.assign(headers,config.header||{});
 const url=new URL('/app-api'+config.url,location.origin);
 for(const [k,v] of Object.entries(config.params||{}))if(v!==undefined&&v!==null)url.searchParams.set(k,v);
 const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),config.timeout||20000);
 let response,body;
 try{response=await fetch(url,{method:config.method||'GET',headers,body:config.data===undefined?undefined:JSON.stringify(config.data),signal:controller.signal});body=await response.json();}
 catch(e){if(config.url.includes('/inventory/'))window.dispatchEvent(new Event('firstsun-stock-error'));throw new Error(e.name==='AbortError'?'请求超时，请重试':'无法连接服务，请检查网络');}
 finally{clearTimeout(timeout);}
 if(!sameIdentity(snapshot,identity()))throw Error('登录账号已变化，请重新加载');
 if(body.code===401||response.status===401){
  if(!custom.skipRefresh&&!config._retried&&sessionStorage.getItem('refresh-token')){
   if(!refreshing){const token=sessionStorage.getItem('refresh-token');refreshing=request({url:'/member/auth/refresh-token',method:'POST',params:{refreshToken:token},custom:{skipRefresh:true,isToken:false}}).then(r=>{if(r.code!==0)throw Error('登录已失效');return r;}).finally(()=>refreshing=null);}
   try{await refreshing;return request({...config,_retried:true});}catch{if(sameIdentity(snapshot,identity()))clearLoginState();}
  }else if(sameIdentity(snapshot,identity()))clearLoginState();
  const error=new Error('登录已失效，请重新登录');error.code=401;throw error;
 }
 if(config.url.includes('/inventory/')&&body.code!==0)window.dispatchEvent(new Event('firstsun-stock-error'));
 if(!response.ok&&!body.code)throw Error('服务暂不可用');
 if(body.code===0&&config.url.startsWith('/member/auth/')&&body.data?.accessToken)setTokens(body.data);
 return body;
}

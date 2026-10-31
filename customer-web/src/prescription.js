import request from './request.js';
import {identity,clearLoginState} from './session.js';
import catalog from '@/sheep/api/pharmacy/server';
import {createPrescriptionApi} from './prescription-core.js';

const api = createPrescriptionApi({
  request,fetch:(...args)=>globalThis.fetch(...args),identity,clearLoginState,FormData:globalThis.FormData,catalog,
  now:()=>Date.now(),random:()=>globalThis.crypto.randomUUID().replace(/-/g,'').slice(0,12),
});
// 退出或令牌失效时丢掉内存中的申请编号和提交摘要，避免下一个账号沿用。
if(typeof window!=='undefined')window.addEventListener('firstsun-auth-clear',()=>api.reset());
export default api;

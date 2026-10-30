// Browser equivalent of the uni storage calls consumed by the unchanged pharmacy server adapter.
// Existing token/session keys are retained; medical conversations stay in Vue memory/server history.
const keys=['token','refresh-token','firstsun-session','firstsun-checkout','firstsun-address-id'];
let epoch=0;
export const identity=()=>({token:sessionStorage.getItem('token')||'',tenant:sessionStorage.getItem('tenant-id')||__TENANT_ID__,epoch});
export function clearLoginState(){epoch++;for(const key of keys)sessionStorage.removeItem(key);window.dispatchEvent(new Event('firstsun-auth-clear'));}
export function setTokens(data){if(!data?.accessToken)throw Error('登录接口未返回有效凭证');sessionStorage.setItem('token',data.accessToken);if(data.refreshToken)sessionStorage.setItem('refresh-token',data.refreshToken);}
export function installUni(){globalThis.uni={getStorageSync(key){const v=sessionStorage.getItem(key);if(!v)return '';try{return JSON.parse(v);}catch{return v;}},setStorageSync(key,value){sessionStorage.setItem(key,typeof value==='string'?value:JSON.stringify(value));},removeStorageSync(key){sessionStorage.removeItem(key);if(key==='token')clearLoginState();}};}

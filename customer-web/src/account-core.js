import {normalizeEmail} from './email-address.js';
// Desktop account requests capture one identity across every step; no refresh or retry.
export function createAccountApi({identity,clearLoginState,setTokens,cacheProfile,fetch,FormData,localTestLogin}){
 const same=(a,b)=>a.token===b.token&&a.tenant===b.tenant&&a.epoch===b.epoch;
 const passwordValid=p=>typeof p==='string'&&p.trim()&&[...p].length>=8&&[...p].length<=64&&new TextEncoder().encode(p).length<=72;
 const ensurePassword=p=>{if(!passwordValid(p))throw Error('密码须为8–64字符且UTF-8不超过72字节，不能全为空白');};
 const mapProfile=raw=>({userId:raw.id,name:raw.nickname||'FirstSun 会员',avatar:raw.avatar||'',mobile:raw.mobile,loginEmail:raw.loginEmail||'',email:raw.email||'',passwordSet:raw.passwordSet===true,level:raw.levelName||'普通会员',points:Number(raw.point)||0});
 function operation(valid=()=>true){
  let owner=identity();
  const check=()=>{if(!valid()||!same(owner,identity()))throw Error('登录账号已变化或操作已取消，请重新加载');};
  async function request(url,{method='GET',data,code,emailCode,file}={}){
   check();const headers={Accept:'application/json',terminal:'20',platform:'H5','tenant-id':owner.tenant};
   if(owner.token)headers.Authorization=owner.token;
   if(code!==undefined)headers['X-Sms-Code']=code;
   if(emailCode!==undefined)headers['X-Email-Code']=emailCode;
   let body;if(file){body=new FormData();body.append('file',file);}else if(data!==undefined){headers['Content-Type']='application/json';body=JSON.stringify(data);}
   const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),20000);
   let response,result;
   try{response=await fetch('/app-api'+url,{method,headers,body,signal:controller.signal});result=await response.json();}finally{clearTimeout(timer);}
   check();
   if(response.status===401||result.code===401){clearLoginState();const e=Error('登录已失效，请重新登录');e.code=401;throw e;}
   if(!response.ok||result.code!==0)throw Error(result.msg||'请求失败，请重试');
   return result.data;
  }
  return{check,request,adopt(data){check();setTokens(data);owner=identity();},clear(){if(same(owner,identity()))clearLoginState();}};
 }
 async function profile(op){const p=mapProfile(await op.request('/member/user/get'));op.check();cacheProfile(p);return p;}
 async function authenticate(url,data,code,valid,channel='sms'){
  if(channel==='sms'&&!localTestLogin)throw Error('此地址不允许本机验收认证');
  if(valid&&!valid())throw Error('操作已取消');
  clearLoginState();const op=operation(valid);
  try{const tokens=await op.request(url,{method:'POST',data,code:channel==='sms'?code:undefined,emailCode:channel==='email'?code:undefined});op.adopt(tokens);return await profile(op);}catch(e){op.clear();throw e;}
 }
 const ensureEmailCode=code=>{if(!/^\d{6}$/.test(code||''))throw Error('请输入6位邮箱验证码');};
 const api={
  async sendEmailCode(email,scene,valid){
   if(!['register','login'].includes(scene))throw Error('验证码用途无效');
   return operation(valid).request('/member/auth/send-email-code',{method:'POST',data:{email:normalizeEmail(email),scene}});
  },
  async registerEmail({email,password},code,valid){
   const data={email:normalizeEmail(email)};ensureEmailCode(code);
   if(password!==undefined&&password!==''){ensurePassword(password);data.password=password;}
   return authenticate('/member/auth/email-register',data,code,valid,'email');
  },
  async loginEmailCode(email,code,valid){
   const data={email:normalizeEmail(email)};ensureEmailCode(code);
   return authenticate('/member/auth/email-login',data,code,valid,'email');
  },
  async loginEmailPassword(email,password,valid){
   const data={email:normalizeEmail(email),password};
   if(typeof password!=='string'||!password.trim())throw Error('请输入邮箱和密码');
   return authenticate('/member/auth/email-password-login',data,undefined,valid,'email');
  },
  async sendEmailFirstPasswordCode(valid){return operation(valid).request('/member/auth/send-email-first-password-code',{method:'POST'});},
  async setEmailFirstPassword(password,code,valid){
   ensurePassword(password);ensureEmailCode(code);
   const op=operation(valid);await op.request('/member/auth/email-first-password',{method:'POST',data:{password},emailCode:code});return profile(op);
  },
  async sendCode(mobile,valid){if(!localTestLogin)throw Error('此地址不允许本机验收认证');if(!/^1[3-9]\d{9}$/.test(mobile))throw Error('请输入正确手机号');return operation(valid).request('/member/auth/send-sms-code?mobile='+encodeURIComponent(mobile),{method:'POST'});},
  async register({mobile,password,email},code,valid){
   if(!/^1[3-9]\d{9}$/.test(mobile))throw Error('请输入正确手机号');
   const data={mobile};if(password!==undefined&&password!==''){ensurePassword(password);data.password=password;}
   if(email!==undefined)data.email=email.trim();
   return authenticate('/member/auth/register',data,code,valid);
  },
  async loginPassword(mobile,password,valid){
   if(!/^1[3-9]\d{9}$/.test(mobile)||typeof password!=='string'||!password.trim())throw Error('请输入手机号和密码');
   // Existing passwords retain their original nonblank policy, including short legacy values.
   return authenticate('/member/auth/login',{mobile,password},undefined,valid);
  },
  async profile(valid){return profile(operation(valid));},
  async logout(valid){const op=operation(valid);try{await op.request('/member/auth/logout',{method:'POST'});}catch{}finally{op.clear();}},
  async saveProfile({nickname,email},valid){
   const op=operation(valid),data={};
   if(nickname!==undefined){data.nickname=nickname.trim();if(!data.nickname||data.nickname.length>30)throw Error('昵称须为1–30个字符');}
   if(email!==undefined){data.email=email.trim();if(data.email.length>255||(data.email&&!/^[^\s@]+@[^\s@]+$/.test(data.email)))throw Error('请输入有效邮箱');}
   await op.request('/member/user/update',{method:'PUT',data});return profile(op);
  },
  async saveAvatar(file,valid){
   if(!file||!['image/png','image/jpeg'].includes(file.type)||file.size<1||file.size>5*1024*1024)throw Error('请选择5MB以内的PNG/JPEG头像');
   const op=operation(valid),avatar=await op.request('/member/user/avatar',{method:'POST',file});
   op.check();await op.request('/member/user/update',{method:'PUT',data:{avatar}});return profile(op);
  },
  async sendPasswordCode(valid){if(!localTestLogin)throw Error('此地址不允许本机验收认证');return operation(valid).request('/member/user/send-password-code',{method:'POST'});},
  async setFirstPassword(password,code,valid){
   if(!localTestLogin)throw Error('此地址不允许本机验收认证');ensurePassword(password);
   const op=operation(valid);await op.request('/member/user/set-password',{method:'POST',data:{password},code});return profile(op);
  },
 };
 return api;
}

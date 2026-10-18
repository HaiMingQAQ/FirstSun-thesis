export function localBackend(value){try{const u=new URL(value);return u.protocol==='http:'&&['127.0.0.1','localhost','[::1]'].includes(u.hostname)&&!u.username&&!u.password&&u.pathname==='/'&&!u.search&&!u.hash?u.origin:'';}catch{return '';}}
export function acceptanceAllowed(mode,flag,backend){return mode==='local-acceptance'&&flag==='true'&&!!localBackend(backend);}
export function sameIdentity(a,b){return a.token===b.token&&a.tenant===b.tenant&&a.epoch===b.epoch;}
export function safeImage(value){if(!value||/^\/?(?:static\/)?pharmacy-demo\//.test(value))return '';try{const u=new URL(value,location.origin);return ['http:','https:'].includes(u.protocol)?u.href:'';}catch{return '';}}

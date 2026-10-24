import {localBackend} from './guards.js';
const avatarPath=/^\/admin-api\/infra\/file\/[1-9]\d*\/get\/member-profile-avatar\/\d{8}\/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.(png|jpg)$/;
// Local acceptance may move ports; stored avatar URLs remain unchanged in the database.
export function avatarUrl(value,localOrigin=''){
 if(typeof value!=='string'||!value)return '';
 const origin=localBackend(localOrigin);if(!origin)return value;
 try{const u=new URL(value);if(['http:','https:'].includes(u.protocol)&&['127.0.0.1','localhost','[::1]'].includes(u.hostname)&&!u.username&&!u.password&&!u.search&&!u.hash&&avatarPath.test(u.pathname))return origin+u.pathname;}catch{}
 return value;
}

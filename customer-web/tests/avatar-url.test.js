import test from 'node:test';
import assert from 'node:assert/strict';
import {avatarUrl} from '../src/avatar-url.js';
import viteConfig from '../vite.config.js';
const path='/admin-api/infra/file/45000044/get/member-profile-avatar/20261024/01234567-89ab-4cde-8fab-0123456789ab.png',origin='http://127.0.0.1:18481';
test('local avatar mapping handles moved loopback ports and both server image extensions',()=>{
 for(const from of ['http://127.0.0.1:58090','http://localhost:58481','http://[::1]:18481'])for(const suffix of ['png','jpg']){const p=path.replace(/png$/,suffix);assert.equal(avatarUrl(from+p,origin),origin+p);}
});
test('production, CDN, non-avatar and noncanonical URLs retain their stored value',()=>{
 const stored='http://127.0.0.1:58090'+path;assert.equal(avatarUrl(stored),stored);
 for(const invalid of ['', 'https://cdn.example.test','http://user:pass@127.0.0.1:18481','http://127.0.0.1:18481/path'])assert.equal(avatarUrl(stored,invalid),stored);
 for(const value of ['https://cdn.example.test'+path,'http://127.0.0.2:58090'+path,'http://user:pass@127.0.0.1:58090'+path,stored+'?download=1',stored+'#fragment',stored.replace('member-profile-avatar','prescription'),stored.replace('/20261024/','/'),stored.replace('01234567-89ab-4cde-8fab-0123456789ab','other'),stored.replace('.png','.svg')])assert.equal(avatarUrl(value,origin),value);
 assert.equal(avatarUrl(null,origin),'');
});
test('Vite exposes the validated local media origin only in local acceptance',()=>{
 const before=process.env.FIRSTSUN_BACKEND_URL;try{process.env.FIRSTSUN_BACKEND_URL=origin;assert.equal(JSON.parse(viteConfig({mode:'production'}).define.__LOCAL_MEDIA_ORIGIN__),'');assert.equal(JSON.parse(viteConfig({mode:'local-acceptance'}).define.__LOCAL_MEDIA_ORIGIN__),origin);process.env.FIRSTSUN_BACKEND_URL='https://remote.example.test';assert.throws(()=>viteConfig({mode:'local-acceptance'}));}finally{if(before===undefined)delete process.env.FIRSTSUN_BACKEND_URL;else process.env.FIRSTSUN_BACKEND_URL=before;}
});

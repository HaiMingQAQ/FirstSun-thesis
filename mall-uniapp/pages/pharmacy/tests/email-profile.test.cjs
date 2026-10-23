const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../../../sheep/api/pharmacy/server.js'),'utf8');
const method=source.slice(source.indexOf('  async profile('),source.indexOf('  async updateProfile(')).trim().replace(/,$/,'');
test('startup adapter preserves email-only verified identity and unverified contact separately',async()=>{
 let cached;const raw={id:19,mobile:null,nickname:'邮箱会员',loginEmail:'verified@example.test',email:'contact@example.test',passwordSet:false};
 const uni={getStorageSync:()=> 'token-A',setStorageSync:(_,p)=>cached=p};
 const profile=new Function('accountCall','uni','SESSION_KEY',`return ({${method}}).profile;`)(async()=>raw,uni,'session');
 const p=await profile();assert.equal(p.loginEmail,raw.loginEmail);assert.equal(p.email,raw.email);assert.equal(p.mobile,null);assert.equal(cached.loginEmail,raw.loginEmail);
});

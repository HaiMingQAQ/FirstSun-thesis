import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {parse,compileScript} from '@vue/compiler-sfc';
for(const file of ['EmailLogin.vue','EmailRegister.vue','EmailFirstPassword.vue'])test(file+' compiles offline with its actual template',()=>{
 const source=fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8'),{descriptor,errors}=parse(source,{filename:file});assert.deepEqual(errors,[]);assert.doesNotThrow(()=>compileScript(descriptor,{id:file,inlineTemplate:true}));
});

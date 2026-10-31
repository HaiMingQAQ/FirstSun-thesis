import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {parse,compileScript} from '@vue/compiler-sfc';
for(const file of ['App.vue','LocalLogin.vue','LocalRegister.vue','PasswordLogin.vue','FirstPassword.vue','AccountPage.vue','ConsultationPage.vue','PrescriptionPages.vue'])test(file+' compiles script and template offline',()=>{
 const source=fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8');const {descriptor,errors}=parse(source,{filename:file});assert.deepEqual(errors,[]);assert.doesNotThrow(()=>compileScript(descriptor,{id:file,inlineTemplate:true}));
});

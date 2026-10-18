import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';
import { localBackend, acceptanceAllowed } from './src/guards.js';
export default defineConfig(({mode})=>{
 const env=loadEnv(mode,process.cwd(),'');
 const backend=localBackend(env.FIRSTSUN_BACKEND_URL);
 if(mode==='local-acceptance'&&!backend)throw Error('Local acceptance requires an explicit loopback backend');
 const testLogin=acceptanceAllowed(mode,env.FIRSTSUN_LOCAL_TEST_LOGIN,env.FIRSTSUN_BACKEND_URL);
 return {plugins:[vue()],resolve:{alias:[{find:'#test-login',replacement:path.resolve(testLogin?'src/LocalLogin.vue':'src/LoginUnavailable.vue')},{find:'@/sheep/request',replacement:path.resolve('src/request.js')},{find:'@/sheep/config',replacement:path.resolve('src/config.js')},{find:'@',replacement:path.resolve('../mall-uniapp')}]},define:{__LOCAL_TEST_LOGIN__:JSON.stringify(testLogin),__TENANT_ID__:JSON.stringify(env.FIRSTSUN_TENANT_ID||'163')},server:{host:'127.0.0.1',fs:{allow:[path.resolve('..')]},proxy:backend?{'/app-api':{target:backend,changeOrigin:true},'/infra':{target:backend,changeOrigin:true}}:{}},build:{sourcemap:false}};
});

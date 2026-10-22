import {createAccountApi} from './account-core.js';
import {identity,clearLoginState,setTokens} from './session.js';
import {localTestLogin} from './config.js';
export default createAccountApi({identity,clearLoginState,setTokens,localTestLogin,fetch:(...args)=>fetch(...args),FormData,
 cacheProfile:profile=>sessionStorage.setItem('firstsun-session',JSON.stringify(profile))});

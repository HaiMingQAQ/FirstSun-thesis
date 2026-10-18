// Same-origin /app-api, proxied to the explicitly configured backend only during local acceptance.
export const baseUrl='';
export const apiPath='/app-api';
export const tenantId=__TENANT_ID__;
export const localTestLogin=__LOCAL_TEST_LOGIN__&&['127.0.0.1','localhost','[::1]'].includes(location.hostname)&&location.protocol==='http:';

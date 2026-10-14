// =====================================================
// 药店小程序统一开关与常量
// -----------------------------------------------------
// PHARMACY_DEMO：
//   true  = 本地 Mock 演示（不请求后端，数据来自 ./fixtures.js）
//   false = 接入真实后端 app-api（默认，课堂演示使用真实接口）
// 开关只在这里配置，页面/组件不允许散落 Mock 判断（见 client.js 门面）。
// =====================================================
export const PHARMACY_DEMO = false;

// 仅本次显式本机验收构建可启用，仍使用真实后端的测试验证码校验。
let h5TestLogin = false;
// #ifdef H5
const localBackend = (value) => {
  try {
    const url = new URL(value);
    if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
      || url.username || url.password || url.pathname !== '/' || url.search || url.hash) return '';
    return url.origin;
  } catch { return ''; }
};
const testBackend = localBackend(import.meta.env.SHOPRO_H5_TEST_BACKEND_URL);
h5TestLogin = import.meta.env.SHOPRO_H5_TEST_LOGIN === 'true'
  && import.meta.env.MODE === 'local-acceptance'
  && import.meta.env.SHOPRO_H5_TEST_BUILD === 'local-acceptance'
  && !!testBackend
  && localBackend(import.meta.env.SHOPRO_BASE_URL) === testBackend
  && localBackend(import.meta.env.SHOPRO_DEV_BASE_URL) === testBackend;
// #endif
export const PHARMACY_H5_TEST_LOGIN = h5TestLogin;

// 仅原本地 Mock 演示使用；本机真实后端验收必须由用户手工填写运行时验证码。
export const TEST_CODE = '123456';

// 处方图片最多上传张数（页面文案与校验共用）
export const MAX_PRESCRIPTIONS = 3;

// 收货地址表单没有地区选择器，后端 area_id 必填，
// 使用演示地区编码（厦门思明区）占位；不影响订单地址快照展示。
export const DEFAULT_AREA_ID = 350203;

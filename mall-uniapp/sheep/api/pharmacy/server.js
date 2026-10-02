// =====================================================
// 药店小程序真实适配器（接入后端 app-api）
// -----------------------------------------------------
// 实现 client.js 门面暴露的同一契约；页面不感知 Mock / 真实切换。
// 所有金额统一为「分」，状态统一为客户端状态机，后端原始结构只在本文件内出现。
// =====================================================
import request, { clearLoginState } from '@/sheep/request';
import { streamConsult } from './sse';
import { baseUrl, apiPath, tenantId } from '@/sheep/config';
import DrugApi from './drug';
import StoreApi from './store';
import CartApi from './cart';
import OrderApi from './order';
import PrescriptionApi from './prescription';
import InventoryApi from './inventory';
import { TEST_CODE, MAX_PRESCRIPTIONS, DEFAULT_AREA_ID } from './config';
import { money, statusNames, normalizeDrug, fen, yuan, formatDate, reviewStatusNames } from './common';

// ---------- 本地会话与结算草稿（与第一版 Mock 共用存储键，避免旧数据残留冲突） ----------
const SESSION_KEY = 'firstsun-session';
const CHECKOUT_KEY = 'firstsun-checkout';
const ADDRESS_KEY = 'firstsun-address-id';
const HISTORY_KEY = 'firstsun-history';
const STORE_KEY = 'firstsun-store';

const clearAuthSession = () => {
  clearLoginState();
  uni.removeStorageSync(SESSION_KEY);
};
const authErrorCode = (error) =>
  error?.code ?? error?.statusCode ?? error?.errCode ?? 'UNKNOWN';
async function authStep(stage, task) {
  const startedAt = Date.now();
  try {
    const result = await task();
    console.info('[FirstSun 微信登录]', {
      stage,
      status: 'success',
      durationMs: Date.now() - startedAt,
      code: 0,
    });
    return result;
  } catch (error) {
    console.info('[FirstSun 微信登录]', {
      stage,
      status: 'failed',
      durationMs: Date.now() - startedAt,
      code: authErrorCode(error),
    });
    throw error;
  }
}
const normalizeAuthError = (error, stage) => {
  if (error instanceof Error && error.message) return error;
  if (error?.msg) {
    const normalized = new Error(error.msg);
    normalized.code = authErrorCode(error);
    return normalized;
  }
  if (stage === 'wechat-sdk') return new Error('微信登录凭证获取失败，请重试');
  if (stage === 'member-profile') return new Error('会员资料加载失败，请重新登录');
  return new Error('微信登录服务暂时不可用，请重试');
};

const draftOwner = () => `${uni.getStorageSync('tenant-id') || tenantId}:${api.session()?.userId || api.session()?.id || api.session()?.mobile || uni.getStorageSync('token') || ''}`;
const getDraft = () => {
  const draft = uni.getStorageSync(CHECKOUT_KEY);
  if (!draft) return null;
  const user = api.session();
  if (!user || draft.mobile !== user.mobile || (draft.owner && draft.owner !== draftOwner())) return null;
  return draft;
};
const setDraft = (draft) => uni.setStorageSync(CHECKOUT_KEY, draft);

// ---------- 请求封装：统一抛业务错误（页面用 useRequest/useAction 捕获并 toast） ----------
async function memberCall(task) {
  if (!api.session()) throw new Error('请先登录');
  const owner = draftOwner();
  const result = await task();
  if (!api.session() || owner !== draftOwner()) throw new Error('登录账号已变化，请重新加载');
  return result;
}
async function accountCall(config) {
  const owner = draftOwner(), token = uni.getStorageSync('token'), tenant = uni.getStorageSync('tenant-id') || tenantId;
  requireOwner(owner);
  try {
    const result = await call({ ...config, header: { Authorization: token, 'tenant-id': tenant },
      custom: { isToken: false, skipTenant: true, skipRefresh: true } });
    requireOwner(owner);
    if (token !== uni.getStorageSync('token')) throw new Error('登录令牌已变化，请重试');
    return result;
  } catch (e) {
    if (e.code === 401 && owner === draftOwner() && token === uni.getStorageSync('token')) clearAuthSession();
    throw e;
  }
}
function requireOwner(owner) {
  if (!api.session() || owner !== draftOwner()) throw new Error('登录账号已变化，请重新加载');
}
async function ownerStep(owner, task) {
  requireOwner(owner);
  const result = await task();
  requireOwner(owner);
  return result;
}
async function call(config) {
  const res = await request({
    ...config,
    custom: {
      auth: false, // 不弹商城登录框，由药店页面自行引导登录
      showLoading: false,
      showError: false, // 错误由本层抛出，避免重复 toast
      isToken: true,
      skipUserInit: true,
      rejectOnError: true,
      ...(config.custom || {}),
    },
  });
  if (!res) throw new Error('网络请求失败，请稍后重试');
  if (res.code !== 0) {
    const err = new Error(res.msg || '请求失败，请稍后重试');
    err.code = res.code;
    throw err;
  }
  return res.data;
}

/**
 * 包装器结果解包（pharmacy 包装器统一关闭了系统 toast，错误由本层抛出）。
 * 兼容两种返回：{code,data} 信封 / 已解包数据。
 */
async function via(promise) {
  const res = await promise;
  if (!res) throw new Error('网络请求失败，请稍后重试');
  const body = res && typeof res === 'object' && 'code' in res ? res : { code: 0, data: res };
  if (body.code !== 0) {
    const err = new Error(body.msg || '请求失败，请稍后重试');
    err.code = body.code;
    throw err;
  }
  return body.data;
}

// ---------- 门店 ----------
// 注意：不可在模块顶层读取 uni 存储（H5 启动期 uni 全局尚未就绪），首次调用时再读缓存。
let store = { id: null, name: 'FirstSun 药店', address: '', hours: '', phone: '' };
let storePromise = null;
async function ensureStore() {
  if (storePromise) return storePromise;
  storePromise = (async () => {
    try {
      if (!store.id) {
        const cached = uni.getStorageSync(STORE_KEY);
        if (cached && cached.id) store = cached;
      }
      const list = await via(StoreApi.getStoreList());
      if (!list || !list.length) throw new Error('暂无营业门店，请稍后再试');
      // 演示环境存在多门店时固定取「FirstSun 演示门店」；找不到时退回编号最小的一家，
      // 保证购物车/下单始终同门店一致
      const raw =
        list.find((s) => s.id === 407 || /FirstSun|演示/.test(s.storeName || '')) ||
        [...list].sort((a, b) => (a.id || 0) - (b.id || 0))[0];
      store = {
        id: raw.id,
        name: raw.storeName || 'FirstSun 药店',
        address: raw.address || '',
        hours: raw.businessHours || '',
        phone: raw.phone || '',
      };
      uni.setStorageSync(STORE_KEY, store);
    } catch (e) {
      storePromise = null;
      throw e;
    }
    return store;
  })();
  return storePromise;
}

// ---------- 药品分类（客户端契约：首位为「全部药品」，供首页 slice(1) 使用） ----------
const CATEGORY_ICONS = { 0: 'medal', 1: 'fire', 2: 'home', 3: 'heart', 4: 'shop', 5: 'list' };
let categoryCache = null;
async function categories() {
  if (categoryCache) return categoryCache;
  const list = await via(DrugApi.getCategoryList());
  const mapped = (list || []).map((c) => ({
    id: c.id,
    name: c.catName,
    icon: CATEGORY_ICONS[c.catType] || 'list',
  }));
  categoryCache = [{ id: 'all', name: '全部药品', icon: 'list' }, ...mapped];
  return categoryCache;
}

// ---------- 库存投影（可售量，只读；写操作全部走后端门禁） ----------
async function availableMap(storeId, drugIds) {
  const ids = (drugIds || []).filter((v) => v != null);
  if (!storeId || !ids.length) return new Map();
  let rows = [];
  try {
    rows = await via(InventoryApi.getAvailable({ storeId, drugIds: ids.join(',') }));
  } catch (e) {
    // 库存服务不可用时不阻塞浏览，按 0 处理并保留原错误供上层提示
    return new Map(ids.map((id) => [Number(id), 0]));
  }
  return new Map((rows || []).map((r) => [Number(r.drugId), Number(r.qtyAvail) || 0]));
}

// ---------- 药品 ----------
const productCache = new Map();
async function fetchDrug(id) {
  const key = Number(id);
  if (productCache.has(key)) return productCache.get(key);
  const raw = await via(DrugApi.getDrug(key));
  const item = normalizeDrug(raw, {
    image: raw.imageUrl || '',
    approval: raw.approvalNo || '',
  });
  productCache.set(key, item);
  return item;
}
async function enrichWithStock(items, storeId) {
  const map = await availableMap(storeId, items.map((p) => p.id));
  return items.map((p) => ({ ...p, stock: map.get(Number(p.id)) ?? p.stock ?? 0 }));
}

const api = {
  get store() {
    return store;
  },
  // 门店列表变化后强制刷新（页面/测试在需要重新拉取门店时调用）
  async refreshStore() {
    storePromise = null;
    uni.removeStorageSync(STORE_KEY);
    store = { id: null, name: 'FirstSun 药店', address: '', hours: '', phone: '' };
    return ensureStore();
  },
  session() {
    if (!uni.getStorageSync('token')) {
      if (uni.getStorageSync(SESSION_KEY)) uni.removeStorageSync(SESSION_KEY);
      return null;
    }
    return (
      uni.getStorageSync(SESSION_KEY) || { name: 'FirstSun 会员', mobile: '', level: '', points: 0 }
    );
  },
  async login(mobile, code) {
    if (!/^1[3-9]\d{9}$/.test(mobile)) throw new Error('请输入正确的 11 位手机号');
    // 1. 获取验证码（后端校验手机号 + 环境是否配置测试码 PHARMACY_DEV_SMS_CODE）
    await call({
      url: '/member/auth/send-sms-code',
      method: 'POST',
      params: { mobile },
    });
    // 2. 登录 / 自动注册；验证码走 X-Sms-Code 请求头（后端契约锁定，避免进访问日志）
    const res = await call({
      url: '/member/auth/login-or-register',
      method: 'POST',
      params: { mobile },
      header: { 'X-Sms-Code': code },
      custom: { skipUserInit: true },
    });
    // 拦截器已按 /member/auth/ 响应自动写入 token；此处补拉会员资料缓存
    const p = await api.profile();
    uni.setStorageSync(SESSION_KEY, p);
    return p;
  },
  async wechatLogin() {
    // #ifdef MP-WEIXIN
    // 微信登录由后端可信配置确定租户/AppID；不要沿用可能过期的租户缓存。
    clearAuthSession();
    uni.removeStorageSync('tenant-id');
    let stage = 'wechat-sdk';
    try {
      const loginResult = await authStep(stage, async () => {
        const result = await uni.login();
        if (!result || result.errMsg !== 'login:ok' || !result.code) {
          throw new Error('微信登录凭证获取失败，请重试');
        }
        return result;
      });
      stage = 'social-login';
      await authStep(stage, () =>
        call({
          url: '/member/auth/social-login',
          method: 'POST',
          data: { code: loginResult.code },
          custom: { isToken: false, skipTenant: true, skipUserInit: true },
        }),
      );
      stage = 'member-profile';
      const p = await authStep(stage, () => api.profile({ skipRefresh: true }));
      uni.setStorageSync(SESSION_KEY, p);
      return p;
    } catch (error) {
      clearAuthSession();
      throw normalizeAuthError(error, stage);
    }
    // #endif
    throw new Error('微信登录仅支持微信小程序');
  },
  async logout() {
    try {
      await call({ url: '/member/auth/logout', method: 'POST', custom: { isToken: true } });
    } catch (e) {
      // 登出失败不阻塞本地清理
    }
    uni.removeStorageSync('token');
    uni.removeStorageSync('refresh-token');
    uni.removeStorageSync(SESSION_KEY);
    uni.removeStorageSync(CHECKOUT_KEY);
    uni.removeStorageSync(ADDRESS_KEY);
  },
  async profile(options = {}) {
    const token = uni.getStorageSync('token');
    const raw = await accountCall({
      url: '/member/user/get',
      method: 'GET',
      custom: { skipRefresh: options.skipRefresh === true },
    });
    if (token !== uni.getStorageSync('token')) throw new Error('登录账号已变化');
    const profile = {
      userId: raw.id,
      name: raw.nickname || 'FirstSun 会员',
      avatar: raw.avatar || '',
      mobile: raw.mobile,
      level: raw.levelName || '普通会员',
      points: Number(raw.point) || 0,
    };
    uni.setStorageSync(SESSION_KEY, profile);
    return profile;
  },
  async updateProfile({ nickname, avatarFile }) {
    const owner = draftOwner(), token = uni.getStorageSync('token');
    requireOwner(owner);
    const data = { nickname: nickname.trim() };
    if (!data.nickname || data.nickname.length > 30) throw new Error('昵称须为 1–30 个字符');
    if (avatarFile) {
      data.avatar = await new Promise((resolve, reject) => uni.uploadFile({
        url: `${baseUrl}${apiPath}/infra/file/upload`, filePath: avatarFile, name: 'file',
        header: { Authorization: token, 'tenant-id': uni.getStorageSync('tenant-id') || tenantId },
        formData: { directory: 'member-avatar' },
        success(r) { try { const body = JSON.parse(r.data); if (r.statusCode !== 200 || body.code !== 0 || typeof body.data !== 'string') throw new Error(); resolve(body.data); } catch { reject(new Error('头像上传失败，请重试')); } },
        fail: () => reject(new Error('头像上传失败，请检查网络')),
      }));
    }
    requireOwner(owner);
    await accountCall({ url: '/member/user/update', method: 'PUT', data });
    requireOwner(owner);
    return api.profile();
  },
  // 页面契约：api.categories 为同步数组（首页 slice(1)、分类页 v-for 直接消费）。
  // 真实数据异步加载，先占位「全部药品」，loadCategories 完成后填充，模板重渲染时自动读取。
  get categories() {
    return categoryCache || [{ id: 'all', name: '全部药品', icon: 'list' }];
  },
  async loadCategories() {
    return categories();
  },
  async products({ keyword = '', category = 'all' } = {}) {
    const s = await ensureStore();
    await categories();
    const page = await via(
      DrugApi.getDrugPage({
        pageNo: 1,
        pageSize: 100,
        categoryId: category === 'all' ? undefined : category,
        keyword: keyword.trim() || undefined,
      }),
    );
    const list = (page?.list || []).map((raw) =>
      normalizeDrug(raw, { image: raw.imageUrl || '', approval: raw.approvalNo || '' }),
    );
    return enrichWithStock(list, s.id);
  },
  async product(id) {
    const s = await ensureStore();
    const item = await fetchDrug(id);
    const map = await availableMap(s.id, [id]);
    item.stock = map.get(Number(id)) ?? 0;
    return item;
  },
  history() {
    return uni.getStorageSync(HISTORY_KEY) || [];
  },
  remember(word) {
    if (!word.trim()) return;
    const list = [word.trim(), ...(api.history().filter((w) => w !== word.trim()))].slice(0, 6);
    uni.setStorageSync(HISTORY_KEY, list);
  },
  clearHistory() {
    uni.setStorageSync(HISTORY_KEY, []);
  },

  async consult({ clientMessageId, content, context }) {
    if (!api.session()) throw new Error('请先登录');
    const store = await ensureStore();
    return call({ url: '/pharmacy/ai/consult', method: 'POST', timeout: 45000,
      data: { clientMessageId, content, context, storeId: store.id } });
  },

  async consultStream({ clientMessageId, content, context, topicId, storeId }, onEvent, signal) {
    if (!api.session()) throw new Error('请先登录');
    const owner = draftOwner(), store = storeId ? { id: storeId } : await ensureStore();
    if (owner !== draftOwner() || !api.session()) throw new Error('登录账号已变化');
    const token = uni.getStorageSync('token');
    return streamConsult(`${baseUrl}${apiPath}/pharmacy/ai/consult/stream`,
      { clientMessageId, content, context, topicId, storeId: store.id },
      { 'Content-Type': 'application/json', Accept: 'text/event-stream',
        Authorization: token, 'tenant-id': uni.getStorageSync('tenant-id') || tenantId }, onEvent, signal)
      .catch(error => { if (error.code === 401 && owner === draftOwner() && token === uni.getStorageSync('token')) clearAuthSession(); throw error; });
  },
  async createAiTopic() { return memberCall(async () => { const store = await ensureStore(); return accountCall({ url: '/pharmacy/ai/topics', method: 'POST', data: { storeId: store.id } }); }); },
  async aiTopics(beforeId) { return accountCall({ url: '/pharmacy/ai/topics', method: 'GET', params: { beforeId } }); },
  async aiTopic(id, beforeId) { return accountCall({ url: `/pharmacy/ai/topics/${id}`, method: 'GET', params: { beforeId } }); },
  async deleteAiTopic(id) { return accountCall({ url: `/pharmacy/ai/topics/${id}`, method: 'DELETE' }); },
  async clearAiTopics() { return accountCall({ url: '/pharmacy/ai/topics', method: 'DELETE' }); },

  async prescriptions() { return memberCall(() => via(PrescriptionApi.getMyPrescriptions())); },
  async prescription(id) { return memberCall(() => via(PrescriptionApi.getPrescription(id))); },
  async uploadMaterial(filePath) {
    if (!api.session()) throw new Error('请先登录');
    const token = uni.getStorageSync('token'), store = await ensureStore();
    if (token !== uni.getStorageSync('token')) throw new Error('登录账号已变化');
    return new Promise((resolve, reject) => uni.uploadFile({
      url: `${baseUrl}${apiPath}/member/prescription/material/upload`, filePath, name: 'file',
      formData: { storeId: store.id }, timeout: 60000,
      header: { Authorization: `Bearer ${token}`, 'tenant-id': String(uni.getStorageSync('tenant-id') || tenantId) },
      success: response => {
        if (token !== uni.getStorageSync('token')) return reject(new Error('登录账号已变化，请重新操作'));
        try { const result = JSON.parse(response.data);
          if (response.statusCode !== 200 || result.code !== 0 || !Number.isSafeInteger(result.data) || result.data <= 0)
            throw new Error(result.msg || '材料上传失败');
          resolve(result.data);
        } catch (error) { reject(error); }
      }, fail: () => reject(new Error('材料上传失败，请检查网络后重试')),
    }));
  },
  async materialImage(id) {
    if (!api.session()) throw new Error('请先登录');
    const token = uni.getStorageSync('token');
    const file = await new Promise((resolve, reject) => uni.downloadFile({
      url: `${baseUrl}${apiPath}/member/prescription/material/get?id=${encodeURIComponent(id)}`,
      header: { Authorization: `Bearer ${token}`, 'tenant-id': String(uni.getStorageSync('tenant-id') || tenantId) },
      success: r => r.statusCode === 200 ? resolve(r.tempFilePath) : reject(new Error('无材料访问权限')),
      fail: () => reject(new Error('材料加载失败')),
    }));
    if (token !== uni.getStorageSync('token')) throw new Error('登录账号已变化');
    await new Promise((resolve, reject) => uni.getImageInfo({ src: file, success: resolve, fail: () => reject(new Error('材料不可读取，请刷新登录后重试')) }));
    return file;
  },
  async submitPrescription({ clientRequestId, patientName, hospital, doctorName, materialIds, items }) {
    if (!api.session()) throw new Error('请先登录');
    const store = await ensureStore();
    return via(PrescriptionApi.createPrescription({ clientRequestId, patientName, hospital, doctorName, materialIds,
      items: items.map(i => ({ drugId: i.drugId, qty: i.qty })), storeId: store.id }));
  },
  async beginPrescriptionCheckout(id) {
    const user = api.session(); if (!user) throw new Error('请先登录');
    const record = await api.prescription(id);
    if (record.reviewStatus !== 1 || record.status !== 0 || !record.approvedItems?.length)
      throw new Error('请等待药师核准后再结算');
    setDraft({ mobile: user.mobile, owner: draftOwner(), buy: null, prescId: id, token: `${Date.now()}-${Math.random()}`, prescriptions: [] });
  },
  async notifications(pageNo = 1) { return memberCall(() => call({ url: '/member/business-notification/page', method: 'GET', params: { pageNo } })); },
  async unreadNotifications() { return memberCall(() => call({ url: '/member/business-notification/unread', method: 'GET' })); },
  async openConsultation(kind) { return memberCall(async () => { const store = await ensureStore(); return call({ url: '/member/consultation/open', method: 'POST', data: { storeId: store.id, kind } }); }); },
  async consultationPage(pageNo = 1) { return memberCall(() => call({ url: '/member/consultation/page', method: 'GET', params: { pageNo } })); },
  async consultationMessages(id, params = {}) { return memberCall(() => call({ url: '/member/consultation/messages', method: 'GET', params: { id, ...params } })); },
  async sendConsultation(data) { return memberCall(() => call({ url: '/member/consultation/send', method: 'POST', data })); },
  async readConsultation(conversationId, throughId) { return memberCall(() => call({ url: '/member/consultation/read', method: 'PUT', data: { conversationId, throughId } })); },
  async readNotification(id) { return memberCall(() => call({ url: '/member/business-notification/read', method: 'PUT', params: { id } })); },

  // ---------- 购物车 ----------
  // drugId -> 购物车记录 id（后端以记录 id 做数量/勾选/删除）
  cartRowIds: new Map(),
  async cart() {
    const s = await ensureStore();
    const rows = (await via(CartApi.getCartList())) || [];
    api.cartRowIds = new Map(rows.map((r) => [Number(r.drugId), r.id]));
    const ids = rows.map((r) => r.drugId);
    const stockMap = await availableMap(s.id, ids);
    const byId = async (id) => {
      try {
        return await fetchDrug(id);
      } catch (e) {
        return null;
      }
    };
    const enriched = [];
    for (const r of rows) {
      const p = (await byId(r.drugId)) || {
        id: r.drugId,
        name: r.drugName || '商品已下架',
        specification: r.specification || '',
        price: Math.round(Number(r.memberPrice ?? (r.retailPrice || 0)) * 100),
        stock: 0,
        rx: Number(r.isRx) === 1,
        device: false,
        active: false,
        image: '',
      };
      p.stock = stockMap.get(Number(r.drugId)) ?? 0;
      enriched.push({ id: r.id, drugId: r.drugId, qty: r.qty, checked: r.selectedFlag === 1, product: p });
    }
    return enriched;
  },
  async add(drugId, qty = 1) {
    const s = await ensureStore();
    const p = await api.product(drugId);
    const current = (await api.cart()).find((r) => r.drugId === drugId)?.qty || 0;
    const want = current + qty;
    if (!p.active) throw new Error('商品已失效，请移除后重试');
    if (!Number.isInteger(qty) || qty < 1 || want > p.stock)
      throw new Error(`库存不足，当前最多可购 ${p.stock} 件`);
    await via(CartApi.addCart({ storeId: s.id, drugId, qty }));
  },
  async updateCart(drugId, patch) {
    const rowId = api.cartRowIds.get(Number(drugId));
    if (!rowId) throw new Error('购物车已更新，请刷新');
    if (patch.qty !== undefined) {
      const p = await api.product(drugId);
      if (!p.active) throw new Error('商品已失效，请移除后重试');
      if (!Number.isInteger(patch.qty) || patch.qty < 1 || patch.qty > p.stock)
        throw new Error(`库存不足，当前最多可购 ${p.stock} 件`);
      await via(CartApi.updateCartQty(rowId, patch.qty));
    }
    if (patch.checked !== undefined) {
      await via(CartApi.updateCartSelected(rowId, patch.checked ? 1 : 0));
    }
  },
  async selectAll(checked) {
    const rows = await api.cart();
    for (const r of rows) {
      const valid = !r.product.rx && r.product.active && r.product.stock >= r.qty;
      const target = checked && valid ? 1 : 0;
      if ((r.checked ? 1 : 0) !== target) {
        await via(CartApi.updateCartSelected(r.id, target));
      }
    }
  },
  async remove(drugId) {
    const rowId = api.cartRowIds.get(Number(drugId));
    if (!rowId) throw new Error('购物车已更新，请刷新');
    await via(CartApi.deleteCart(rowId));
  },

  // ---------- 结算 ----------
  beginCheckout(buy = null) {
    const user = api.session();
    if (!user) throw new Error('请先登录');
    setDraft({
      mobile: user.mobile,
      owner: draftOwner(),
      buy,
      token: `${Date.now()}-${Math.random()}`,
      prescriptions: [],
    });
  },
  async checkout() {
    return memberCall(async () => {
    const draft = getDraft();
    if (!draft) throw new Error('结算已失效，请重新选择商品');
    const prescription = draft.prescId ? await api.prescription(draft.prescId) : null;
    if (prescription && (prescription.reviewStatus !== 1 || prescription.status !== 0 || !prescription.approvedItems?.length
      || !Number.isFinite(new Date(prescription.approvedUntil).getTime())
      || new Date(prescription.approvedUntil).getTime() <= Date.now())) throw new Error('处方未核准或已超过有效期，请联系药师');
    const items = prescription ? await Promise.all(prescription.approvedItems.map(async i => ({ drugId: i.drugId, qty: i.qty, product: await api.product(i.drugId) }))) : draft.buy
      ? [{ drugId: draft.buy.id, qty: draft.buy.qty, product: await api.product(draft.buy.id) }]
      : (await api.cart()).filter((r) => r.checked);
    if (!items.length) throw new Error('请先选择要结算的商品');
    // 结算前逐项复核最新库存与价格（库存 / 价格可能已变化）
    for (const r of items) {
      const p = await api.product(r.drugId);
      if (p.rx && !prescription) throw new Error('处方药需先提交申请并经药师审核');
      if (!p.active) throw new Error(`「${p.name}」已下架，请移除后重试`);
      if (r.qty > p.stock) throw new Error(`「${p.name}」库存不足，当前最多可购 ${p.stock} 件`);
      r.product = p;
    }
    const subtotal = items.reduce((s, r) => s + r.product.price * r.qty, 0);
    // 配送费：后端订单按 0 计算（配送费规则未配置），以服务端口径展示
    const shipping = { delivery: 0, pickup: 0 };
    // 积分与抵扣试算：以服务端 deduct-preview 为准，页面不自行折算
    let points = 0,
      maxUsablePoints = 0,
      maxDeductFen = 0,
      pointsPerYuan = 100,
      maxDeductPoints = 0;
    try {
      const summary = await call({ url: '/member/point/summary', method: 'GET' });
      points = Number(summary?.memberPoint) || 0;
      pointsPerYuan = Number(summary?.pointsPerYuan) || 100;
      maxDeductPoints = Number(summary?.maxDeductPoints) || 0;
      const preview = await call({
        url: '/member/point/deduct-preview',
        method: 'POST',
        data: { orderAmount: yuan(subtotal), usePoints: 0 },
      });
      maxUsablePoints = Number(preview?.deductPoints) || 0;
      maxDeductFen = Math.round(Number(preview?.deductAmount || 0) * 100);
    } catch (e) {
      // 积分能力不可用时仅展示 0，不阻塞结算
    }
    return {
      items,
      points,
      maxUsablePoints,
      maxDeductFen,
      pointsPerYuan,
      maxDeductPoints,
      pointsRule: `${pointsPerYuan} 积分抵 ¥1`,
      maxDeductNote: maxDeductPoints ? `每单最多抵 ¥${(maxDeductPoints / pointsPerYuan).toFixed(0)}` : '',
      address: await api.defaultAddress(),
      prescriptions: prescription?.materialIds || draft.prescriptions || [],
      prescription,
      shipping,
      draft,
    };
    });
  },
  prescriptionDraft() {
    const draft = getDraft();
    return draft ? draft.prescriptions || [] : [];
  },
  async savePrescriptions(images) {
    const draft = getDraft();
    if (!draft) throw new Error('请先从确认订单页上传处方');
    if (!images || !images.length) throw new Error('请先选择处方图片');
    if (images.length > MAX_PRESCRIPTIONS) throw new Error(`最多上传 ${MAX_PRESCRIPTIONS} 张处方`);
    throw new Error('处方私有上传服务尚未接通，请联系门店办理');
  },

  // ---------- 创建订单（防重复下单：提交锁 + 草稿 orderId 幂等） ----------
  createLock: null,
  async createOrder({ mode, address, usePoints, remark }) {
    // 并发/连点保护：同一时刻只允许一笔下单在途，后续调用复用同一结果
    if (api.createLock) return api.createLock;
    api.createLock = (async () => {
      const owner = draftOwner();
      requireOwner(owner);
      const draft = getDraft();
      if (!draft) throw new Error('结算已失效，请重新选择商品');
      if (draft.orderId) return ownerStep(owner, () => api.order(draft.orderId));
      const s = await ownerStep(owner, () => ensureStore());
      if (!['delivery', 'pickup'].includes(mode)) throw new Error('请选择配送或自提');
      // 立即购买：后端按「购物车已勾选商品」创建订单，先把该商品放入购物车并勾选
      const data = await ownerStep(owner, () => api.checkout());
      const orderType = mode === 'delivery' ? 1 : 0;
      let addressId = null;
      if (orderType === 1) {
        if (!address || !address.id) throw new Error('请选择收货地址');
        addressId = address.id;
      }
      const prescId = draft.prescId || null;
      if (data.items.some(r => r.product.rx) && !prescId) throw new Error('处方药需经药师审核');
      // 使用积分：只表达「是否用满服务端试算的最大可用积分」，具体抵扣由后端 calcSalePoints 校验
      const use = usePoints ? data.maxUsablePoints : 0;
      if (prescId) {
        // Only fixed approved items are selected; final exact-match and single-use checks remain server-side.
        const old = (await ownerStep(owner, () => via(CartApi.getCartList()))) || [];
        for (const row of old) if (row.selectedFlag === 1) await ownerStep(owner, () => via(CartApi.updateCartSelected(row.id, 0)));
        for (const row of data.items) await api.ensureBuyInCart(s, { id: row.drugId, qty: row.qty }, true, owner);
      } else if (draft.buy) await api.ensureBuyInCart(s, draft.buy, false, owner);
      const id = await ownerStep(owner, () => via(
        OrderApi.createOrder({
          storeId: s.id,
          orderType,
          addressId,
          prescId,
          remark: (remark || '').trim(),
          usePoints: use,
        }),
      ));
      setDraft({ ...draft, orderId: id });
      return ownerStep(owner, () => api.order(id));
    })().finally(() => {
      api.createLock = null;
    });
    return api.createLock;
  },
  async ensureBuyInCart(s, buy, keepOtherSelected = false, owner = draftOwner()) {
    const rows = await ownerStep(owner, () => api.cart());
    for (const r of rows) {
      if (!keepOtherSelected && r.checked && r.drugId !== Number(buy.id))
        await ownerStep(owner, () => via(CartApi.updateCartSelected(r.id, 0)));
    }
    const row = rows.find((r) => r.drugId === Number(buy.id));
    if (row) {
      if (row.qty !== buy.qty) await ownerStep(owner, () => via(CartApi.updateCartQty(row.id, buy.qty)));
      if (!row.checked) await ownerStep(owner, () => via(CartApi.updateCartSelected(row.id, 1)));
    } else {
      await ownerStep(owner, () => via(CartApi.addCart({ storeId: s.id, drugId: buy.id, qty: buy.qty })));
      const fresh = (await ownerStep(owner, () => via(CartApi.getCartList()))) || [];
      const added = fresh.find((r) => r.drugId === Number(buy.id));
      if (added && added.selectedFlag !== 1) await ownerStep(owner, () => via(CartApi.updateCartSelected(added.id, 1)));
    }
  },

  // ---------- 订单 ----------
  async mockPaymentAvailable() {
    try {
      return (await via(OrderApi.getMockPaymentAvailable())) === true;
    } catch (_) {
      return false;
    }
  },
  async orders() {
    const page = await via(OrderApi.getOrderPage({ pageNo: 1, pageSize: 50 }));
    const list = page?.list || [];
    const result = [];
    for (const raw of list) result.push(await mapOrder(raw));
    return result;
  },
  async order(id) {
    const raw = await via(OrderApi.getOrder(id));
    return mapOrder(raw);
  },
  async orderAction(id, action) {
    if (action === 'cancel') {
      await via(OrderApi.cancelOrder(id, '用户主动取消'));
    } else if (action === 'pay') {
      // 客户端状态门禁：与订单操作按钮一致，待审核/已支付订单不允许再次模拟支付
      const order = await api.order(id);
      if (order.status !== 'unpaid') throw new Error('订单状态已变化，请刷新后重试');
      await via(OrderApi.simulatePay(id));
    } else if (action === 'receive') {
      const order = await api.order(id);
      if (!order.canReceive) throw new Error('订单状态已变化，请刷新后重试');
      await via(OrderApi.confirmReceive(id));
    } else {
      throw new Error('不支持的操作');
    }
  },

  // ---------- 地址 ----------
  async addresses() {
    const list = await call({ url: '/member/address/list', method: 'GET' });
    return (list || []).map((a) => ({
      id: a.id,
      name: a.name,
      mobile: a.mobile,
      detail: a.detailAddress,
      isDefault: !!a.defaultStatus,
    }));
  },
  async defaultAddress() {
    let selectedId = uni.getStorageSync(ADDRESS_KEY);
    const list = await api.addresses();
    if (selectedId) {
      const hit = list.find((a) => a.id === selectedId);
      if (hit) return hit;
    }
    return list.find((a) => a.isDefault) || list[0] || null;
  },
  async saveAddress(address) {
    if (!address.name || !address.name.trim()) throw new Error('请填写收货人');
    if (!/^1[3-9]\d{9}$/.test(address.mobile)) throw new Error('请输入正确的 11 位手机号');
    if (!address.detail || !address.detail.trim()) throw new Error('请填写完整地址');
    const payload = {
      id: address.id,
      name: address.name.trim(),
      mobile: address.mobile.trim(),
      areaId: DEFAULT_AREA_ID,
      detailAddress: address.detail.trim(),
      defaultStatus: !!address.isDefault,
    };
    if (payload.id) await call({ url: '/member/address/update', method: 'PUT', data: payload });
    else await call({ url: '/member/address/create', method: 'POST', data: payload });
    return api.defaultAddress();
  },
  async deleteAddress(id) {
    await call({ url: '/member/address/delete', method: 'DELETE', params: { id } });
    if (uni.getStorageSync(ADDRESS_KEY) === id) uni.removeStorageSync(ADDRESS_KEY);
  },
  chooseAddress(id) {
    uni.setStorageSync(ADDRESS_KEY, id);
  },
};

// ---------- 私有：订单映射（后端 WxOrderRespVO → 客户端契约） ----------
async function mapOrder(raw) {
  const s = await ensureStore();
  const presc = raw.prescId ? await fetchPrescSafe(raw.prescId) : null;
  const status = toStatus(raw, presc);
  const lines = raw.lines || [];
  const items = [];
  for (const line of lines) {
    let p = null;
    try {
      p = await fetchDrug(line.drugId);
    } catch (e) {
      p = null;
    }
    items.push({
      drugId: line.drugId,
      qty: line.qty,
      // 历史订单使用服务端订单行价格，不能用当前目录价覆盖。
      product: p ? { ...p, price: fen(line.price) } : {
        id: line.drugId,
        name: line.drugName,
        specification: line.specification,
        unit: line.unit,
        price: fen(line.price),
        stock: 0,
        rx: false,
        device: false,
        active: true,
        image: '',
      },
    });
  }
  const order = {
    items,
    canReceive: raw.orderType === 1 && raw.payStatus === 1 && raw.status === 3,
    id: raw.id,
    orderNo: raw.orderNo,
    status,
    paid: raw.payStatus === 1,
    paymentLabel: raw.payStatus === 2 ? '已退款' : raw.payStatus === 1 ? '已支付' : '未支付',
    mode: raw.orderType === 1 ? 'delivery' : 'pickup',
    store: { name: s.name, address: s.address, hours: s.hours },
    subtotal: fen(raw.goodsAmount),
    shipping: fen(raw.freightAmount),
    points: fen(raw.pointDeductAmount),
    total: fen(raw.payableAmount),
    createdAt: formatDate(raw.createTime),
    remark: raw.remark || '',
    address: { name: '', mobile: '', detail: raw.addressSnapshot || '请联系门店核实收货信息' },
    prescriptions: presc?.images || [],
    review: presc ? reviewStatusNames[presc.reviewStatus] || '处方已提交，请等待药师审核' : raw.prescId ? '暂无法获取审核状态，请稍后重试' : '无需处方',
  };
  return order;
}

function toStatus(raw, presc) {
  if (raw.status === -1) return 'cancelled';
  if (raw.status === 4) return 'completed';
  if (raw.payStatus === 1) return 'ready'; // 待拣货 / 拣货中 / 待自提 均按「待收取」展示
  if (raw.status === 0) {
    // 含处方药且审方仍待审 → 进入「待审核」；通过后回落到「待支付」
    if (presc && Number(presc.reviewStatus) === 0) return 'review';
    return 'unpaid';
  }
  return 'unpaid';
}

async function fetchPrescSafe(id) {
  try {
    return await via(PrescriptionApi.getPrescription(id));
  } catch (e) {
    return null;
  }
}

export default api;

// =====================================================
// 药店小程序公共转换层（Mock 与真实适配器共用）
// -----------------------------------------------------
// 页面只消费「客户端契约」，不直接依赖后端原始结构；
// 金额统一为「分」，日期统一为本地可读文本，状态统一为客户端状态机。
// =====================================================

/** 分 → 元字符串（两位小数） */
export const money = (cents = 0) => (Number(cents) / 100).toFixed(2);

/** 元（后端 Decimal） → 分 */
export const fen = (yuan) => Math.round(Number(yuan || 0) * 100);

/** 分 → 元（给后端试算接口用，后端按元计算） */
export const yuan = (cents) => Number(Number(cents || 0) / 100).toFixed(2);

/** 后端 LocalDateTime（如 2026-09-19T10:30:00） → 本地文本 */
export const formatDate = (value) => {
  if (!value) return '';
  if (typeof value === 'number') {
    const date = new Date(value), pad = n => String(n).padStart(2, '0');
    if (Number.isNaN(date.getTime())) return '';
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }
  const text = String(value).replace('T', ' ').replace(/\..*$/, '');
  return text.length >= 16 ? text.slice(0, 16) : text;
};

export const statusNames = {
  unpaid: '待支付',
  review: '待审核',
  ready: '履约中',
  completed: '已完成',
  cancelled: '已取消',
};

export function orderStatusName(order) {
  if (order.status !== 'ready') return statusNames[order.status];
  // 本地旧 Mock 的 ready 可直接确认取药/收货，没有独立的备货阶段。
  if (order.readyForHandoff === false) return '备货中';
  return order.mode === 'pickup' ? '可到店自提' : '待收货';
}

// Payment capability is not evidence of how any individual order was paid.
export function orderPaymentLabel(order) {
  if ('payStatus' in order) {
    return ({ 0: '未支付', 1: '已支付', 2: '已退款' })[order.payStatus] || '付款状态待核实';
  }
  if (['未支付', '已支付', '已退款'].includes(order.paymentLabel)) return order.paymentLabel;
  return order.paid === true ? '已支付' : order.paid === false ? '未支付' : '付款状态待核实';
}

const PHARMACY_DEMO_IMAGES = {
  '/pharmacy-demo/medicine.svg': '/static/pharmacy-demo/medicine.svg',
  '/pharmacy-demo/traditional.svg': '/static/pharmacy-demo/traditional.svg',
  '/pharmacy-demo/health.svg': '/static/pharmacy-demo/health.svg',
  '/pharmacy-demo/device.svg': '/static/pharmacy-demo/device.svg',
  '/pharmacy-demo/cold-chain.svg': '/static/pharmacy-demo/cold-chain.svg',
};

/** 将管理后台提供的演示占位图路径映射到随小程序打包的本地资源。 */
export function resolvePharmacyImage(image) {
  return PHARMACY_DEMO_IMAGES[image] || image || '';
}

/** 处方审核状态文案（后端 0 待审 / 1 通过 / 2 驳回） */
export const reviewStatusNames = {
  0: '处方已提交，请等待药师审核',
  1: '处方审核已通过',
  2: '处方审核未通过，请联系药师',
};

/**
 * 后端 AppDrugRespVO → 客户端 product 契约。
 * @param raw 后端药品 VO（含 imageUrl/approvalNo）
 * @param availability 库存投影 { stock, image, approval, active }
 */
export function normalizeDrug(raw, availability = {}) {
  return {
    id: raw.id,
    name: raw.tradeName || raw.genericName,
    genericName: raw.genericName,
    specification: raw.specification,
    manufacturer: raw.manufacturer,
    category: raw.categoryId,
    barcode: availability.barcode || '',
    approval: availability.approval || '商品档案暂未提供',
    image: resolvePharmacyImage(availability.image),
    // 会员价优先（与后端下单取价口径一致），单位为分
    price: Math.round(Number(raw.memberPrice ?? (raw.retailPrice || 0)) * 100),
    stock: Math.max(0, Math.floor(Number(availability.stock) || 0)),
    rx: Number(raw.isRx) === 1,
    device: Number(raw.drugType) === 6,
    active: availability.active !== false,
  };
}

// Match the desktop allowlist: migrate only local member-avatar asset URLs between backend ports.
export function memberAvatarUrl(value, mediaBase) {
  if (typeof value !== 'string' || !value) return '';
  const local = (url) => ['http:', 'https:'].includes(url.protocol) &&
    ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) &&
    !url.username && !url.password && !url.search && !url.hash;
  try {
    const source = new URL(value), target = new URL(mediaBase);
    const path = /^\/admin-api\/infra\/file\/[1-9]\d*\/get\/member-profile-avatar\/\d{8}\/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\.(?:png|jpg)$/i;
    if (local(source) && local(target) && target.pathname === '/' && path.test(source.pathname)) {
      return target.origin + source.pathname;
    }
  } catch { /* Relative or invalid values are not rewritten. */ }
  return value;
}

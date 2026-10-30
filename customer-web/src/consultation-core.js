// 门店文字咨询的纯前端规则：编号、去重和门店归属。服务端仍是权限与去重的最终依据。

const REQUEST_ID = /^[a-zA-Z0-9_-]{8,64}$/;

export function kindLabel(kind) {
  if (kind === 'PHARMACIST') return '药师文字咨询';
  if (kind === 'SERVICE') return '门店客服';
  return '咨询类型待核实';
}

// 只把与当前门店编号一致的会话标成当前门店，旧门店会话保留真实编号。
export function storeAttribution(item, store) {
  if (!item || item.storeId == null) return '门店归属未提供';
  const id = `#${item.storeId}`;
  if (store == null || store.id == null) return `门店 ${id}`;
  if (Number(item.storeId) === Number(store.id)) {
    const name = store.name ? ` ${store.name}` : '';
    return `当前门店${name} · ${id}`;
  }
  return `其他门店 · ${id}`;
}

export function mergeById(existing, incoming) {
  const known = new Set((existing || []).map((item) => item.id));
  return [...(existing || []), ...(incoming || []).filter((item) => item && !known.has(item.id))];
}

export function prependById(older, existing) {
  const known = new Set((existing || []).map((item) => item.id));
  return [...(older || []).filter((item) => item && !known.has(item.id)), ...(existing || [])];
}

export function newClientRequestId(now = Date.now(), random = Math.random().toString(36).slice(2, 10)) {
  const id = `web_${now}_${random}`.replace(/[^a-zA-Z0-9_-]/g, '');
  if (!REQUEST_ID.test(id)) throw Error('请求编号无效');
  return id;
}

export function sameClientRequest(left, right) {
  return !!left && !!right && left.clientRequestId === right.clientRequestId && left.content === right.content && left.conversationId === right.conversationId;
}

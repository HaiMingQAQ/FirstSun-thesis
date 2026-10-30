// 电脑端处方申请：只消费会员本人接口，不生成审核结果，也不发起结算或付款。
const STALE = () => Object.assign(new Error('登录账号已变化，请重新加载'), {code:'STALE'});
const textOf = value => String(value ?? '');
const chars = value => [...textOf(value)].length;

export function createClientRequestId(now = Date.now(), entropy = '') {
  const id = `presc_${now}_${entropy}`.replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 64);
  if (!/^[a-zA-Z0-9_-]+$/.test(id)) throw Error('申请编号无效');
  return id;
}

export function imageKind(bytes) {
  const data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes || []);
  if (data.length >= 8 && [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a].every((byte, index) => data[index] === byte)) return 'image/png';
  if (data.length >= 3 && data[0] === 0xff && data[1] === 0xd8 && data[2] === 0xff) return 'image/jpeg';
  return '';
}

export function assertMaterialBytes(bytes, size = bytes?.byteLength ?? bytes?.length ?? 0) {
  if (!Number.isFinite(size) || size < 1 || size > 5 * 1024 * 1024) throw Error('仅接受不超过 5MB 的 PNG/JPEG 图片');
  const mime = imageKind(bytes);
  if (!mime) throw Error('图片格式不合法，仅接受 PNG/JPEG');
  return mime;
}

export function assertImageSize(width, height) {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0 || width > 8000 || height > 8000 || width * height > 20000000) throw Error('图片尺寸过大或无法读取');
}

export function reviewLabel(record) {
  if (!record || typeof record !== 'object') return '状态待确认';
  if (Number(record.status) === 2) return '已作废';
  return {0:'待药师审核', 1:'审核通过', 2:'审核未通过'}[Number(record.reviewStatus)] || '状态待确认';
}

export function recordState(record) {
  return {0:'有效', 1:'已完成', 2:'已作废'}[Number(record?.status)] || '记录状态待确认';
}

export function rejectionReason(record) {
  if (Number(record?.status) === 2 || Number(record?.reviewStatus) !== 2) return '';
  const text = textOf(record.reviewOpinion).trim();
  return text || '药师未填写驳回原因';
}

export function reviewNote(record) {
  if (Number(record?.status) === 2) return '该申请已作废，不能作为购买依据。';
  const status = Number(record?.reviewStatus);
  if (status === 0) return '已提交，等待药师审核。提交不代表审核通过。';
  if (status === 1) return textOf(record?.reviewOpinion).trim();
  if (status === 2) return rejectionReason(record);
  return '审核状态暂无法确认，请稍后刷新。不要把申请内容当作审核结果。';
}

export function useLabel(status) {
  return {HELD:'待支付占用', PAID:'已支付使用', RELEASED:'未支付关闭'}[status] || '使用状态待确认';
}

export function submissionSnapshot(payload) {
  return JSON.stringify({
    storeId: payload.storeId,
    patientName: payload.patientName,
    hospital: payload.hospital,
    doctorName: payload.doctorName,
    materialIds: payload.materialIds,
    items: payload.items.map(item => [item.drugId, item.qty]),
  });
}

export function validateApplication(input) {
  const patientName = textOf(input?.patientName).trim();
  const hospital = textOf(input?.hospital).trim();
  const doctorName = textOf(input?.doctorName).trim();
  const storeId = Number(input?.storeId);
  if (!patientName || patientName.length > 32 || chars(patientName) > 32) throw Error('请填写不超过 32 字的患者姓名');
  if (hospital.length > 100 || chars(hospital) > 100) throw Error('医院名称不超过 100 字');
  if (doctorName.length > 32 || chars(doctorName) > 32) throw Error('医师姓名不超过 32 字');
  if (!Number.isSafeInteger(storeId) || storeId <= 0) throw Error('请先确认当前门店');
  const materialIds = Array.isArray(input?.materialIds) ? input.materialIds.map(Number) : [];
  if (materialIds.length < 1 || materialIds.length > 3 || new Set(materialIds).size !== materialIds.length) throw Error('请上传 1–3 张不同处方图片');
  if (materialIds.some(id => !Number.isSafeInteger(id) || id <= 0)) throw Error('材料尚未上传完成');
  const source = Array.isArray(input?.items) ? input.items : [];
  if (!source.length || source.length > 20) throw Error('请选择 1–20 项药品');
  const seen = new Set();
  const items = source.map(item => {
    const drugId = Number(item?.drugId), qty = Number(item?.qty);
    if (!Number.isSafeInteger(drugId) || drugId <= 0) throw Error('药品无效');
    if (!Number.isSafeInteger(qty) || qty < 1 || qty > 999) throw Error('每种药品数量须为 1–999');
    if (seen.has(drugId)) throw Error('药品不得重复');
    seen.add(drugId);
    return {drugId, qty};
  });
  return {storeId, patientName, hospital, doctorName, materialIds, items};
}

function timeValue(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  const parsed = Date.parse(textOf(value).replace(' ', 'T'));
  return Number.isFinite(parsed) ? parsed : 0;
}

function normalizeItems(value) {
  if (!Array.isArray(value)) return [];
  return value.map(item => ({
    drugId: Number(item?.drugId) || null,
    qty: Number.isFinite(Number(item?.qty)) ? Number(item.qty) : null,
    drugName: item?.drugName || '名称未返回',
    specification: item?.specification || '',
    usage: item?.usage || '',
    dosage: item?.dosage || '',
  }));
}

export function normalizeRecord(raw) {
  if (!raw || typeof raw !== 'object') throw Error('处方记录返回异常');
  const id = Number(raw.id);
  if (typeof raw.id !== 'number' || !Number.isSafeInteger(id) || id <= 0) throw Error('处方编号无法确认');
  return {
    id,
    prescNo: raw.prescNo || '',
    storeId: typeof raw.storeId === 'number' ? raw.storeId : null,
    patientName: raw.patientName || '',
    hospital: raw.hospital || '',
    doctorName: raw.doctorName || '',
    createTime: raw.createTime,
    reviewStatus: raw.reviewStatus,
    reviewOpinion: raw.reviewOpinion || '',
    status: raw.status,
    materialIds: Array.isArray(raw.materialIds) ? raw.materialIds.filter(item => typeof item === 'number' && Number.isSafeInteger(item) && item > 0) : [],
    requestedItems: normalizeItems(raw.requestedItems),
    approvedItems: normalizeItems(raw.approvedItems),
    approvedUntil: raw.approvedUntil ?? null,
    uses: Array.isArray(raw.uses) ? raw.uses.map(use => ({
      wxOrderId: typeof use?.wxOrderId === 'number' ? use.wxOrderId : null,
      status: use?.status || '',
      releaseReason: use?.releaseReason || '',
      createTime: use?.createTime,
    })) : [],
  };
}

export function sortRecords(records) {
  return [...records].sort((a, b) => timeValue(b.createTime) - timeValue(a.createTime) || b.id - a.id);
}

function positiveId(value, message) {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value <= 0) throw Error(message);
  return value;
}

function failure(message, code) {
  const error = new Error(message || '服务返回异常');
  if (code !== undefined) error.code = code;
  return error;
}

export function createPrescriptionApi({request, fetch, identity, clearLoginState, FormData, catalog, now = () => Date.now(), random = () => ''}) {
  let revision = 0, draft = null, inflight = false;
  const same = (a, b) => !!a && !!b && a.token === b.token && a.tenant === b.tenant && a.epoch === b.epoch;
  const owns = owner => !!owner?.token && same(owner, identity());
  function assertOwner(owner) {
    if (!identity().token) throw failure('请先登录', 401);
    if (!owns(owner)) throw STALE();
  }
  function reset() { revision++; draft = null; inflight = false; }
  function rememberDraft(owner, snapshot) {
    if (!draft || !owns(draft.owner) || draft.snapshot !== snapshot) draft = {owner, snapshot, requestId: createClientRequestId(now(), random()), uncertain: false};
    return draft;
  }
  async function memberJson(owner, config) {
    assertOwner(owner);
    const body = await request({...config, custom:{...(config.custom || {}), skipRefresh:true}});
    assertOwner(owner);
    if (!body || body.code !== 0) throw failure(body?.msg || '服务返回异常', body?.code);
    return body.data;
  }
  async function ensureStore(owner = identity()) {
    assertOwner(owner);
    if (!catalog.store?.id) await catalog.refreshStore();
    assertOwner(owner);
    const id = Number(catalog.store?.id);
    if (!Number.isSafeInteger(id) || id <= 0) throw Error('暂无营业门店，请稍后再试');
    return {id, name: catalog.store.name || '当前门店', address: catalog.store.address || ''};
  }
  async function guardedFetch(owner, url, options, timeout, fallback) {
    assertOwner(owner);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    try {
      return await fetch(url, {...options, signal: controller.signal, cache: 'no-store'});
    } catch (error) {
      throw Error(error?.name === 'AbortError' ? fallback : '无法连接服务，请检查网络');
    } finally { clearTimeout(timer); }
  }
  function expire(owner, response) {
    assertOwner(owner);
    if (response.status === 401) {
      if (owns(owner)) clearLoginState();
      throw failure('登录已失效，请重新登录', 401);
    }
  }
  const api = {
    reset,
    submission() { if (draft && !owns(draft.owner)) draft = null; return draft ? {requestId: draft.requestId, uncertain: !!draft.uncertain} : null; },
    ensureStore,
    async list() {
      const owner = identity();
      const rows = await memberJson(owner, {url:'/member/prescription/page', method:'GET'});
      assertOwner(owner);
      if (!Array.isArray(rows)) throw Error('处方列表返回异常');
      return sortRecords(rows.map(normalizeRecord));
    },
    async detail(id) {
      const owner = identity();
      if (typeof id !== 'number') throw Error('申请链接无效');
      positiveId(id, '申请链接无效');
      const row = await memberJson(owner, {url:'/member/prescription/get', method:'GET', params:{id}});
      assertOwner(owner);
      return normalizeRecord(row);
    },
    async drug(id) {
      const owner = identity();
      const store = await ensureStore(owner);
      const product = await catalog.product(id);
      assertOwner(owner);
      if (Number(catalog.store?.id) !== store.id) throw Error('门店已变化，请重新选择药品');
      const drugId = Number(product?.id);
      if (!Number.isSafeInteger(drugId) || drugId <= 0) throw Error('药品无效');
      return {id: drugId, name: product.name || '未命名药品', specification: product.specification || '', rx: product.rx === true || Number(product.isRx) === 1};
    },
    async search(keyword) {
      const owner = identity();
      const store = await ensureStore(owner);
      const word = textOf(keyword).trim();
      if (!word) return [];
      const page = await catalog.productPage({keyword: word, pageNo: 1, pageSize: 10});
      assertOwner(owner);
      if (Number(catalog.store?.id) !== store.id) throw Error('门店已变化，请重新搜索');
      return (page?.list || []).slice(0, 10).map(product => ({
        id: Number(product.id),
        name: product.name || product.genericName || '未命名药品',
        specification: product.specification || '',
        rx: product.rx === true || Number(product.isRx) === 1,
      })).filter(product => Number.isSafeInteger(product.id) && product.id > 0);
    },
    async upload(file, storeId) {
      const owner = identity();
      const store = await ensureStore(owner);
      if (Number(storeId) !== store.id) throw Error('门店已变化，请重新上传处方图片');
      const bytes = new Uint8Array(await file.arrayBuffer());
      assertOwner(owner);
      assertMaterialBytes(bytes, file.size);
      const body = new FormData();
      body.append('storeId', String(store.id));
      body.append('file', file, file.name || (imageKind(bytes) === 'image/png' ? 'prescription.png' : 'prescription.jpg'));
      const response = await guardedFetch(owner, '/app-api/member/prescription/material/upload', {
        method: 'POST',
        headers: {Accept:'application/json', Authorization: owner.token, 'tenant-id': owner.tenant, terminal:'20', platform:'H5'},
        body,
      }, 60000, '材料上传超时，请重试');
      const raw = await response.text();
      assertOwner(owner);
      if (Number(catalog.store?.id) !== store.id) throw Error('门店已变化，请重新上传处方图片');
      expire(owner, response);
      let parsed = null;
      try { parsed = raw ? JSON.parse(raw) : null; } catch { parsed = null; }
      if (parsed?.code === 401) { if (owns(owner)) clearLoginState(); throw failure('登录已失效，请重新登录', 401); }
      if (!response.ok || !parsed || parsed.code !== 0) throw failure(parsed?.msg || '材料上传失败', parsed?.code);
      return positiveId(parsed.data, '材料编号无法确认');
    },
    async material(id) {
      const owner = identity();
      positiveId(typeof id === 'number' ? id : Number.NaN, '材料编号无效');
      const response = await guardedFetch(owner, `/app-api/member/prescription/material/get?id=${encodeURIComponent(id)}`, {
        method: 'GET',
        headers: {Accept:'image/png,image/jpeg', Authorization: owner.token, 'tenant-id': owner.tenant, terminal:'20', platform:'H5'},
      }, 20000, '材料加载超时，请重试');
      const bytes = new Uint8Array(await response.arrayBuffer());
      assertOwner(owner);
      expire(owner, response);
      const mime = imageKind(bytes);
      if (!response.ok || !mime) {
        let message = response.status === 403 ? '无材料访问权限' : '材料加载失败';
        try { const parsed = JSON.parse(new TextDecoder().decode(bytes)); if (parsed?.msg) message = parsed.msg; if (parsed?.code === 401) { if (owns(owner)) clearLoginState(); throw failure('登录已失效，请重新登录', 401); } } catch (error) { if (error.code === 401) throw error; }
        throw failure(message, response.status);
      }
      return {mime, bytes};
    },
    async submit(input) {
      if (inflight) throw Error('申请正在提交，请等待结果');
      inflight = true;
      const owner = identity();
      let dispatched = false, version = revision, snapshot = null;
      try {
        const store = await ensureStore(owner);
        if (input?.storeId !== undefined && Number(input.storeId) !== store.id) throw Error('门店已变化，请确认材料后重新提交');
        const payload = validateApplication({...input, storeId: store.id});
        snapshot = submissionSnapshot(payload);
        const current = rememberDraft(owner, snapshot);
        version = revision;
        dispatched = true;
        const id = positiveId(await memberJson(owner, {url:'/member/prescription/create', method:'POST', timeout:20000, data:{...payload, clientRequestId: current.requestId}}), '申请编号无法确认');
        assertOwner(owner);
        if (version !== revision) throw STALE();
        draft = null;
        return id;
      } catch (error) {
        if (snapshot && version === revision && owns(owner) && draft?.snapshot === snapshot) {
          const uncertain = dispatched && (error.code == null || (Number(error.code) >= 500 && Number(error.code) < 600));
          draft.uncertain = uncertain;
          if (uncertain) error.uncertain = true;
          if (!uncertain && /不一致|冲突/.test(error.message || '')) draft = null;
        }
        throw error;
      } finally { inflight = false; }
    },
  };
  return api;
}

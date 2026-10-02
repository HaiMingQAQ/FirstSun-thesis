<template>
  <s-pharmacy-page>
    <s-pharmacy-state v-if="!loggedIn" title="登录后查看处方" action="去登录" @retry="go('login')" />
    <template v-else>
      <view class="fs-section fs-between"><view class="fs-title">处方资料</view><button class="fs-outline" :disabled="busy" @tap="newForm">提交新申请</button></view>
      <view class="fs-notice">私有材料仅限本人及有权限的本门店药师读取。提交不代表审核通过，须按核准明细购买。</view>
      <s-pharmacy-state v-if="loading || error" :loading="loading" :error="error" @retry="load" />
      <view v-if="showForm" class="fs-section">
        <view class="fs-title">提交处方申请</view>
        <view class="fs-field"><text>患者姓名</text><input v-model="form.patientName" maxlength="32" :disabled="busy" placeholder="填写实际患者姓名" /></view>
        <view class="fs-field"><text>开具医院</text><input v-model="form.hospital" maxlength="100" :disabled="busy" placeholder="按处方填写" /></view>
        <view class="fs-field"><text>医师姓名</text><input v-model="form.doctorName" maxlength="32" :disabled="busy" placeholder="按处方填写" /></view>
        <view class="fs-title fs-gap">申请药品与数量</view><view class="fs-muted">申请值交由药师核对；用法用量由药师审核后填写。</view>
        <view class="fs-row fs-gap"><input v-model="keyword" class="fs-grow" :disabled="busy" placeholder="搜索药品名称" /><button class="fs-outline" :disabled="busy || !keyword.trim()" @tap="search">搜索</button></view>
        <button v-for="product in results" :key="product.id" class="fs-text-btn fs-gap" :disabled="busy" @tap="select(product)">{{ product.name }} · {{ product.specification }}</button>
        <view v-for="item in items" :key="item.drugId" class="fs-gap">
          <view>{{ item.name }} <text class="fs-muted">{{ item.specification }}</text></view>
          <view class="fs-between fs-gap"><s-pharmacy-stepper v-model="item.qty" :max="999" :disabled="busy" /><button class="fs-text-btn" :disabled="busy" @tap="items = items.filter(i => i !== item)">移除</button></view>
        </view>
        <view class="fs-title fs-gap">处方材料 · 1–3 张 PNG/JPEG</view><view class="fs-muted">单张不超过 5MB；失败不会显示上传或审核成功。</view>
        <view v-for="(file,index) in files" :key="file.path" class="fs-row fs-gap"><image :src="file.path" class="material-thumb" mode="aspectFit" /><text class="fs-grow">{{ file.id ? '已私有上传，尚未提交' : '待上传' }}</text><button class="fs-text-btn" :disabled="busy" @tap="files.splice(index,1)">移除</button></view>
        <button class="fs-outline fs-gap" :disabled="busy || files.length >= 3" @tap="choose">选择处方图片</button>
        <button class="fs-primary fs-gap" :disabled="busy || !form.patientName.trim() || !items.length || !files.length" :loading="busy" @tap="submit">{{ busy ? '提交中…' : '提交给药师审核' }}</button>
      </view>
      <view v-if="selected" class="fs-section">
        <view class="fs-between"><text class="fs-title">{{ selected.prescNo }}</text><text class="fs-tag fs-tag-rx">{{ reviewLabel(selected) }}</text></view>
        <view class="fs-muted fs-gap">患者：{{ selected.patientName }} · {{ formatDate(selected.createTime) }}</view>
        <view class="fs-gap">药师意见：{{ selected.reviewOpinion || '等待药师审核' }}</view>
        <view class="fs-row fs-gap material-actions"><button v-for="id in selected.materialIds" :key="id" class="fs-outline" :disabled="busy" @tap="preview(id)">查看私有材料</button></view>
        <view v-for="item in selected.approvedItems" :key="item.drugId" class="fs-gap"><view>{{ item.drugName }} × {{ item.qty }}</view><view class="fs-muted">{{ item.specification }} · {{ item.usage }} · {{ item.dosage }}</view></view>
        <view v-if="selected.approvedUntil" class="fs-muted fs-gap">核准有效期：{{ formatDate(selected.approvedUntil) }}</view>
        <view v-for="use in selected.uses || []" :key="use.wxOrderId" class="fs-gap"><view>{{ use.status === 'RELEASED' ? '未支付关闭 · 可在有效期内重新使用' : use.status === 'PAID' ? '已支付使用' : '待支付占用' }}</view><button class="fs-text-btn" @tap="go('order-detail?id=' + use.wxOrderId)">查看订单 {{ use.wxOrderId }}</button></view>
        <button v-if="canBuy" class="fs-primary fs-gap" :disabled="busy" @tap="checkout">按核准明细结算</button>
        <button v-if="selected.reviewStatus === 2" class="fs-outline fs-gap" :disabled="busy" @tap="amend">修改后重新提交新申请</button>
      </view>
      <s-pharmacy-state v-if="!loading && !error && !records.length && !showForm" title="暂无处方记录" description="可提交真实材料交由药师审核" />
      <view v-for="record in records" :key="record.id" class="fs-section" @tap="open(record.id)"><view class="fs-between"><text>{{ record.prescNo }}</text><text class="fs-tag">{{ reviewLabel(record) }}</text></view><view class="fs-muted">{{ formatDate(record.createTime) }}</view><button class="fs-text-btn">查看申请与核准明细</button></view>
    </template>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref, reactive, computed } from 'vue';
  import { onLoad, onShow } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { formatDate } from '@/sheep/api/pharmacy/common';
  import { go, toast, accountKey, useRequest, useAction } from './usePharmacy';
  const loggedIn = ref(false), records = ref([]), selected = ref(null), showForm = ref(false), keyword = ref(''), results = ref([]), items = ref([]), files = ref([]);
  const form = reactive({ patientName: '', hospital: '', doctorName: '' });
  const { loading, error, run } = useRequest(), { busy, act } = useAction();
  let owner = '', initialDrug = null, initialId = null, requestId = '', lastSubmission = '';
  const reviewLabel = r => r.status === 2 ? '已作废' : ['待药师审核','审核通过','审核未通过'][r.reviewStatus] || '状态待确认';
  const canBuy = computed(() => selected.value?.status === 0 && selected.value.reviewStatus === 1
    && selected.value.approvedItems?.length && new Date(selected.value.approvedUntil).getTime() > Date.now()
    && !(selected.value.uses || []).some(u => ['HELD','PAID'].includes(u.status)));
  const load = () => run(async () => { records.value = await api.prescriptions(); if (selected.value) selected.value = await api.prescription(selected.value.id); });
  const open = id => run(async () => { selected.value = await api.prescription(id); showForm.value = false; });
  const newForm = () => { showForm.value = true; selected.value = null; };
  const search = () => run(async () => { results.value = await api.products({ keyword: keyword.value.trim() }); if (!results.value.length) toast('未找到药品'); });
  const select = product => { if (items.value.length >= 20) { toast('单次申请最多 20 项药品'); return; } if (!items.value.some(i => i.drugId === product.id)) items.value.push({ drugId: product.id, qty: 1, name: product.name, specification: product.specification }); results.value = []; };
  const choose = () => uni.chooseImage({ count: 3 - files.value.length, sizeType: ['compressed'], success: r => { for (const path of r.tempFilePaths) files.value.push({ path, id: null }); } });
  const submit = () => act(async () => {
    const account = accountKey();
    for (const file of files.value) if (!file.id) file.id = await api.uploadMaterial(file.path);
    if (account !== accountKey()) throw new Error('登录账号已变化');
    const data = { ...form, patientName: form.patientName.trim(), materialIds: files.value.map(f => f.id), items: items.value };
    const snapshot = JSON.stringify(data);
    if (!requestId || snapshot !== lastSubmission) requestId = `presc_${Date.now()}_${Math.random().toString(36).slice(2,10)}`;
    lastSubmission = snapshot;
    const id = await api.submitPrescription({ ...data, clientRequestId: requestId });
    if (account !== accountKey()) throw new Error('登录账号已变化');
    selected.value = await api.prescription(id); records.value = await api.prescriptions(); showForm.value = false; files.value = []; items.value = []; toast('申请已提交，等待药师审核');
  });
  const preview = id => act(async () => { const path = await api.materialImage(id); uni.previewImage({ urls: [path] }); });
  const amend = () => act(async () => {
    const record = selected.value, account = accountKey(), materials = [];
    for (const id of record.materialIds) materials.push({ id, path: await api.materialImage(id) });
    if (account !== accountKey()) throw new Error('登录账号已变化');
    form.patientName = record.patientName || ''; form.hospital = record.hospital || ''; form.doctorName = record.doctorName || '';
    items.value = (record.requestedItems || []).map(i => ({ drugId: i.drugId, qty: i.qty, name: i.drugName, specification: i.specification }));
    files.value = materials; requestId = ''; lastSubmission = ''; newForm();
  });
  const checkout = () => act(async () => { await api.beginPrescriptionCheckout(selected.value.id); go('checkout'); });
  onLoad(q => { initialDrug = Number(q.drugId) || null; initialId = Number(q.id) || null; });
  onShow(async () => {
    const account = accountKey(); loggedIn.value = !!api.session();
    if (account !== owner) { records.value = []; selected.value = null; files.value = []; items.value = []; results.value = []; form.patientName = ''; form.hospital = ''; form.doctorName = ''; showForm.value = false; requestId = ''; } owner = account;
    if (!loggedIn.value) return;
    await load();
    if (initialId) { await open(initialId); initialId = null; }
    if (initialDrug) { const id = initialDrug; initialDrug = null; newForm(); run(async () => select(await api.product(id))); }
  });
</script>
<style scoped>
  .material-thumb { width: 64px; height: 64px; flex-shrink: 0; }
  .material-actions { flex-wrap: wrap; }
</style>

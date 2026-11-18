<template>
  <el-badge v-if="count !== null && count > 0" :value="count" :max="99" class="ml-8px" />
</template>
<script setup lang="ts">
import { getDrugPage } from '@/api/pharmacy/base/drug'
import { checkPermi } from '@/utils/permission'
const route = useRoute()
const count = ref<number | null>(null)
let generation = 0
const refresh = async () => {
  if (!checkPermi(['pharmacy:base:drug:query']) || !checkPermi(['pharmacy:base:drug:approve']))
    return
  const ticket = ++generation
  try {
    const data = await getDrugPage({ pageNo: 1, pageSize: 1, approveStatus: 0 })
    if (ticket === generation) count.value = data.total
  } catch {
    if (ticket === generation) count.value = null
  }
}
watch(() => route.fullPath, refresh, { immediate: true })
onMounted(() => window.addEventListener('pharmacy-drug-review-updated', refresh))
onBeforeUnmount(() => window.removeEventListener('pharmacy-drug-review-updated', refresh))
</script>

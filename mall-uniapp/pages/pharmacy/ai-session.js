import { reactive } from 'vue';
// Page switches retain this session in memory; nothing medical goes into localStorage.
export const aiSession = reactive({ owner: '', topicId: null, storeId: null, messages: [] });

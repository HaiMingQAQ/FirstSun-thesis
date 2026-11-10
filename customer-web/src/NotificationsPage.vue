<script setup>
import {onMounted,onUnmounted,ref} from 'vue';
import api from '@/sheep/api/pharmacy/server';
import {formatDate} from '@/sheep/api/pharmacy/common';
import {identity} from './session.js';
import {sameIdentity} from './guards.js';

const props=defineProps({user:Object});
const emit=defineEmits(['login','read']);
const notices=ref([]),total=ref(0),page=ref(1),loading=ref(false),reading=ref(null),error=ref('');
const owner=identity();
let disposed=false;
const current=()=>!disposed&&!!props.user&&sameIdentity(owner,identity());

async function load(more=false){
  if(!current()||loading.value||reading.value!==null)return;
  const next=more?page.value+1:1;
  loading.value=true;
  error.value='';
  try{
    const result=await api.notifications(next);
    if(!current())return;
    const items=result?.list??[];
    notices.value=more?[...notices.value,...items.filter(item=>!notices.value.some(saved=>saved.id===item.id))]:items;
    total.value=result?.total??notices.value.length;
    page.value=next;
  }catch(e){if(current())error.value=e.message||'消息加载失败，请重试。';}
  finally{if(current())loading.value=false;}
}
async function markRead(notice){
  if(!current()||loading.value||reading.value!==null||notice.readStatus)return;
  reading.value=notice.id;
  error.value='';
  try{
    if(await api.readNotification(notice.id)!==true)throw Error('未能标记已读，请重试。');
    if(!current())return;
    notice.readStatus=true;
    emit('read');
  }catch(e){if(current())error.value=e.message||'未能标记已读，请重试。';}
  finally{if(current())reading.value=null;}
}
function prescriptionHref(notice){
  const id=String(notice.reference?.prescId??'');
  return /^[1-9][0-9]*$/.test(id)?'#rx-detail?id='+id:null;
}
onMounted(()=>load());
onUnmounted(()=>{disposed=true;});
</script>

<template>
  <main class="customer-page notification-page">
    <div class="breadcrumb"><a href="#home">首页</a><Icon name="chevron-right"/><span>消息通知</span></div>
    <div class="customer-title"><div><p class="eyebrow">FIRSTSUN / NOTIFICATIONS</p><h1>消息通知</h1><p>查看处方审核等与会员账号相关的通知。</p></div><button v-if="user" class="btn notice-button" type="button" :disabled="loading||reading!==null" @click="load()"><Icon name="refresh-cw"/>刷新</button></div>
    <div v-if="!user" class="paper empty-state"><Icon name="bell"/><h2>登录后查看消息</h2><p>仅当前会员可查看自己的通知。</p><button class="btn primary" type="button" @click="emit('login')">登录会员账号</button></div>
    <template v-else>
      <p v-if="error" class="error-panel" role="alert">{{error}}</p>
      <div v-if="loading&&!notices.length" class="paper empty-state" role="status">正在读取通知…</div>
      <div v-else-if="!notices.length&&!error" class="paper empty-state"><Icon name="bell"/><h2>暂无消息</h2><p>有新的会员通知时，会显示在这里。</p></div>
      <section v-else-if="notices.length" class="paper notice-list" aria-label="通知列表">
        <article v-for="notice in notices" :key="notice.id" class="notice-item">
          <span class="notice-icon"><Icon :name="prescriptionHref(notice)?'file-check-2':'bell'"/></span>
          <div class="notice-content">
            <div class="notice-meta"><span :class="{unread:!notice.readStatus}">{{notice.readStatus?'已读':'未读'}}</span><span>{{formatDate(notice.createTime)||'时间未提供'}}</span></div>
            <p>{{notice.content}}</p>
            <div class="notice-actions">
              <a v-if="prescriptionHref(notice)" class="btn link" :href="prescriptionHref(notice)">查看处方<Icon name="arrow-right"/></a>
              <button v-if="!notice.readStatus" class="btn link" type="button" :disabled="loading||reading!==null" @click="markRead(notice)">{{reading===notice.id?'正在标记…':'标为已读'}}</button>
            </div>
          </div>
        </article>
        <div v-if="notices.length<total" class="pagination"><button class="btn" type="button" :disabled="loading||reading!==null" @click="load(true)">加载更多</button></div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.notification-page { max-width: 960px; padding-bottom: 48px; }
.notice-list { border-radius: 16px; }
.notice-item { display: flex; align-items: flex-start; gap: 14px; padding: clamp(14px,3vw,22px); }
.notice-item + .notice-item { border-top: 1px solid var(--line); }
.notice-icon { display: grid; place-items: center; width: 42px; height: 42px; flex: none; border-radius: 10px; color: var(--green); background: #edf5ef; }
.notice-icon svg { width: 20px; height: 20px; }
.notice-content { min-width: 0; }
.notice-content p { margin: 8px 0 0; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.notice-meta, .notice-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; }
.notice-meta { font-size: 12px; color: var(--muted); }
.notice-meta .unread { color: var(--green); font-weight: 700; }
.notice-actions:not(:empty) { margin-top: 8px; }
.notice-actions .btn, .notice-button { display: inline-flex; align-items: center; gap: 6px; }
.notice-actions .btn { padding-inline: 0; }
.notice-actions svg, .notice-button svg { width: 16px; height: 16px; }
</style>

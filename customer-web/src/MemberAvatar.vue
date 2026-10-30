<script setup>
import {computed,ref,watch} from 'vue';
import {avatarUrl} from './avatar-url.js';
import {localMediaOrigin} from './config.js';
const props=defineProps({src:String,alt:{type:String,default:'会员头像'},size:{type:Number,default:28}});
const imageUrl=computed(()=>avatarUrl(props.src,localMediaOrigin)),failedUrl=ref('');
watch(imageUrl,()=>{failedUrl.value='';},{flush:'sync'});
function onError(event){failedUrl.value=event.currentTarget.getAttribute('src')||'';}
</script>
<template><span class="member-avatar" :style="{width:props.size+'px',height:props.size+'px'}"><img v-if="imageUrl&&failedUrl!==imageUrl" :key="imageUrl" :src="imageUrl" :alt="props.alt" @error="onError"><span v-else class="avatar-placeholder" :role="props.alt?'img':undefined" :aria-label="props.alt?'默认头像':undefined" :aria-hidden="!props.alt||undefined"><Icon name="circle-user-round"/></span></span></template>
<style scoped>
.member-avatar{display:inline-flex;flex-shrink:0;vertical-align:middle;border-radius:50%;overflow:hidden}
.member-avatar img{display:block;width:100%;height:100%;object-fit:cover}
.avatar-placeholder{display:flex;align-items:center;justify-content:center;width:100%;height:100%;background:#eef4ee}
.avatar-placeholder svg{width:60%;height:60%}
</style>

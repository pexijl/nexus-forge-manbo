<script setup lang="ts"></script>

<template>
  <Toast position="top-left" group="tl" />
  <Toast position="top-right" group="tr" />
  <Toast position="bottom-right" group="br" />
  <Toast position="bottom-left" group="bl" />
  <ConfirmDialog group="positioned"></ConfirmDialog>
  <router-view />
</template>

<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue';
import { useToast } from 'primevue/usetoast';
import { useRouter } from 'vue-router';

const toast = useToast();
const router = useRouter();

const onAuthExpired = (e: Event) => {
  const message = (e as CustomEvent).detail;
  toast.add({ severity: 'warn', summary: '登录已过期', detail: message, group: 'tr', life: 3000 });
  router.push({ name: 'auth-view' });   // ← 唯一跳转点
};

onMounted(() => globalThis.addEventListener('auth:expired', onAuthExpired));
onUnmounted(() => globalThis.removeEventListener('auth:expired', onAuthExpired));
</script>

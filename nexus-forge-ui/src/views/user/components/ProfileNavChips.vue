<template>
  <nav class="profile-nav-chips" aria-label="账号设置">
    <div class="nav-chips-inner" role="tablist">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        class="chip"
        role="tab"
        :aria-current="active === tab.id ? 'true' : 'false'"
        @click="$emit('switch', tab.id)"
      >
        {{ tab.label }}
      </button>
    </div>
  </nav>
</template>

<script setup lang="ts">
defineProps<{ active: string }>();
defineEmits<{ (e: 'switch', tab: string): void }>();

const tabs = [
  { id: 'profile', label: '基础资料' },
  { id: 'contact', label: '联系方式' },
  { id: 'notifications', label: '通知与隐私' },
  { id: 'security', label: '账号安全' },
];
</script>

<style scoped lang="scss">
.profile-nav-chips {
  /* sticky 在 mobile 时吸顶在 toolbar 下方 */
  position: sticky;
  top: var(--shell-topbar-h);
  z-index: 20;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}

.nav-chips-inner {
  max-width: var(--container-max);
  margin-inline: auto;
  padding: var(--space-2) var(--container-gutter-phone);
  display: flex;
  gap: var(--space-2);
  overflow-x: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.nav-chips-inner::-webkit-scrollbar {
  display: none;
}
</style>

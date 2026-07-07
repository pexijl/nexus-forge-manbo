<template>
  <nav class="profile-nav-chips" aria-label="账号设置">
    <div class="nav-chips-inner" role="tablist">
      <router-link
        v-for="tab in accountTabs"
        :key="tab.id"
        :to="{ name: tab.routeName }"
        :aria-current="isActive(tab) ? 'true' : 'false'"
        class="chip"
        role="tab"
      >
        {{ tab.label }}
      </router-link>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router';
import { accountTabs } from '../constants/profileTabs';

const route = useRoute();

function isActive(tab: { routeName?: string }) {
  return route.name === tab.routeName;
}
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

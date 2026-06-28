<template>
  <div class="profile-view">
    <ProfileNavChips class="mobile-only" :active="active" @switch="onSwitch" />

    <div class="profile-app">
      <ProfileSidebar class="desktop-only" :active="active" @switch="onSwitch" />

      <main class="profile-content">
        <BasicPanel v-if="active === 'profile'" />
        <ContactPanel v-else-if="active === 'contact'" />
        <NotificationPanel v-else-if="active === 'notifications'" />
        <SecurityPanel v-else-if="active === 'security'" />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import ProfileSidebar from './components/ProfileSidebar.vue';
import ProfileNavChips from './components/ProfileNavChips.vue';
import BasicPanel from './panels/BasicPanel.vue';
import ContactPanel from './panels/ContactPanel.vue';
import NotificationPanel from './panels/NotificationPanel.vue';
import SecurityPanel from './panels/SecurityPanel.vue';

const STORAGE_KEY = 'account-center.active';
const VALID = ['profile', 'contact', 'notifications', 'security'] as const;

const active = ref<(typeof VALID)[number]>('profile');

// 还原上次激活的 tab
try {
  const last = sessionStorage.getItem(STORAGE_KEY);
  if (last && (VALID as readonly string[]).includes(last)) {
    active.value = last as (typeof VALID)[number];
  }
} catch {
  /* ignore */
}

// 持久化当前 tab
watch(active, (v) => {
  try {
    sessionStorage.setItem(STORAGE_KEY, v);
  } catch {
    /* ignore */
  }
});

function onSwitch(tab: string) {
  if ((VALID as readonly string[]).includes(tab)) {
    active.value = tab as (typeof VALID)[number];
    // 切 tab 时回到顶部, 避免从长面板跳到短面板时停在中间
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
}
</script>

<style scoped lang="scss">
.profile-view {
  background: var(--bg);
  color: var(--fg);
  min-height: 100%;
}

.profile-app {
  max-width: var(--container-max);
  margin-inline: auto;
  width: 100%;
  padding-inline: var(--container-gutter-desktop);
  display: grid;
  grid-template-columns: var(--shell-sidebar-w) 1fr;
  gap: var(--space-8);
  align-items: start;
}

.profile-content {
  padding-block: var(--space-8);
  max-width: var(--shell-content-max);
  width: 100%;
  min-width: 0;
}

@media (max-width: 1023px) {
  .profile-app {
    grid-template-columns: 200px 1fr;
    gap: var(--space-6);
    padding-inline: var(--container-gutter-tablet);
  }
}

@media (max-width: 767px) {
  .profile-app {
    grid-template-columns: 1fr;
    gap: 0;
    padding-inline: 0;
  }
  .profile-content {
    padding: var(--space-5) var(--container-gutter-phone) var(--space-12);
  }
}

/* 显隐：桌面显示 sidebar，移动显示 chips */
.desktop-only {
  display: block;
}
.mobile-only {
  display: none;
}

@media (max-width: 767px) {
  .desktop-only {
    display: none !important;
  }
  .mobile-only {
    display: block;
  }
}
</style>

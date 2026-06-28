<template>
  <div class="profile-view">
    <ProfileNavChips class="mobile-only"/>

    <div class="profile-app">
      <ProfileSidebar class="desktop-only" />

      <main class="profile-content">
        <router-view/>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import ProfileSidebar from './components/ProfileSidebar.vue';
import ProfileNavChips from './components/ProfileNavChips.vue';
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

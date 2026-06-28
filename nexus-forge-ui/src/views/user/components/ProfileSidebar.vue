<template>
  <aside class="profile-sidebar" aria-label="账号设置">
    <div class="sidebar-section">
      <div class="sidebar-label eyebrow">账号</div>
      <nav aria-label="个人中心导航" class="nav-list">
        <Button
          v-for="tab in accountTabs"
          :key="tab.id"
          type="button"
          role="tab"
          :aria-current="active === tab.id ? 'true' : 'false'"
          :label="tab.label"
          :icon="tab.icon"
          :badge="tab.badge || undefined"
          :badgeSeverity="tab.badgeVariant || 'secondary'"
          :severity="active === tab.id ? 'primary' : 'secondary'"
          variant="text"
          rounded
          @click="$emit('switch', tab.id)"
          class=""
          pt:root="profile-sidebar-nav-btn-root"
          pt:label="my-custom-label-class"
        />
      </nav>
    </div>

    <div class="sidebar-section">
      <div class="sidebar-label eyebrow">工作区</div>
      <nav class="nav-list" aria-label="工作区">
        <Button
          v-for="link in workspaceLinks"
          :key="link.label"
          :label="link.label"
          :icon="link.icon"
          variant="text"
          severity="secondary"
          rounded
          class="nav-item"
        />
      </nav>
    </div>
  </aside>
</template>

<script setup lang="ts">
import Button from 'primevue/button';

defineProps<{
  active: string;
}>();

defineEmits<{
  (e: 'switch', tab: string): void;
}>();

const accountTabs = [
  { id: 'profile', label: '基础资料', icon: 'pi pi-user' },
  { id: 'contact', label: '联系方式', icon: 'pi pi-envelope' },
  {
    id: 'notifications',
    label: '通知与隐私',
    icon: 'pi pi-bell',
    badge: '3',
    badgeVariant: 'muted',
  },
  {
    id: 'security',
    label: '账号安全',
    icon: 'pi pi-shield',
    badge: '已开启',
    badgeVariant: 'success',
    badgeDot: true,
  },
];

const workspaceLinks = [
  { label: '团队成员', icon: 'pi pi-users' },
  { label: '账单与订阅', icon: 'pi pi-file-invoice' },
  { label: '集成与 API', icon: 'pi pi-plug' },
];
</script>

<style scoped lang="scss">
.profile-sidebar {
  align-self: start;
  padding-block: var(--space-8);
  /* sticky 让 sidebar 跟随页面滚动保持可见 */
  position: sticky;
  top: calc(var(--shell-topbar-h) + var(--space-6));
}

.sidebar-section {
  margin-bottom: var(--space-6);
}

.sidebar-label {
  padding-inline: var(--space-3);
  margin-bottom: var(--space-2);
}

.nav-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.profile-sidebar-nav-btn-root {
  width: 100%;
  justify-content: flex-start;
}

.nav-item {
  width: 100%;
  justify-content: flex-start;
}

/* 为工作区链接添加不同样式 */
.nav-list:last-child .nav-item {
  opacity: 0.8;
}

.nav-list:last-child .nav-item:hover {
  opacity: 1;
}
</style>

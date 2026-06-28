<template>
  <aside class="profile-sidebar" aria-label="账号设置">
    <div class="sidebar-section">
      <div class="sidebar-label eyebrow">账号</div>
      <nav aria-label="个人中心导航" class="nav-list">
        <Button
          v-for="tab in accountTabs"
          :key="tab.id"
          unstyled
          :pt="{
            root: { class: 'profile-sidebar-nav-btn' },
            label: 'profile-sidebar-nav-label',
            icon: 'profile-sidebar-nav-icon',
          }"
          :aria-current="active === tab.id ? 'true' : 'false'"
          @click="$emit('switch', tab.id)"
        >
          <template #default>
            <i :class="tab.icon" class="profile-sidebar-nav-icon" />
            <span class="profile-sidebar-nav-label">{{ tab.label }}</span>
            <span
              v-if="tab.badge"
              class="badge profile-sidebar-nav-badge"
              :class="`badge-${tab.badgeVariant || 'muted'}`"
            >
              <span v-if="tab.badgeDot" class="badge-dot" aria-hidden="true" />
              {{ tab.badge }}
            </span>
          </template>
        </Button>
      </nav>
    </div>

    <div class="sidebar-section">
      <div class="sidebar-label eyebrow">工作区</div>
      <nav class="nav-list nav-list-workspace" aria-label="工作区">
        <Button
          v-for="link in workspaceLinks"
          :key="link.label"
          unstyled
          :pt="{
            root: { class: 'profile-sidebar-nav-btn profile-sidebar-nav-btn--muted' },
            label: 'profile-sidebar-nav-label',
            icon: 'profile-sidebar-nav-icon',
          }"
        >
          <template #default>
            <i :class="link.icon" class="profile-sidebar-nav-icon" />
            <span class="profile-sidebar-nav-label">{{ link.label }}</span>
          </template>
        </Button>
      </nav>
    </div>
  </aside>
</template>

<script setup lang="ts">
import Button from 'primevue/button';

type BadgeVariant = 'info' | 'success' | 'warn' | 'muted';

defineProps<{
  active: string;
}>();

defineEmits<{
  (e: 'switch', tab: string): void;
}>();

const accountTabs: Array<{
  id: string;
  label: string;
  icon: string;
  badge?: string;
  badgeVariant?: BadgeVariant;
  badgeDot?: boolean;
}> = [
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

const workspaceLinks: Array<{ label: string; icon: string }> = [
  { label: '团队成员', icon: 'pi pi-users' },
  { label: '账单与订阅', icon: 'pi pi-wallet' },
  { label: '集成与 API', icon: 'pi pi-sparkles' },
];
</script>

<style scoped lang="scss">
/* ── Sidebar 容器 ──────────────────────────────────────────── */
.profile-sidebar {
  align-self: start;
  padding-block: var(--space-8);
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

/* ── Nav list ──────────────────────────────────────────────── */
.nav-list {
  display: flex;
  flex-direction: column;
  gap: 2px; /* 与参考 HTML 一致：紧凑的导航密度 */
}

/* ── Nav button —— 完全对齐参考 HTML 的 .nav-item ────────── */
.profile-sidebar-nav-btn {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 8px var(--space-3);
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--fg);
  font-size: var(--text-sm);
  font-weight: 400;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
  width: 100%;
  transition: background-color var(--motion-fast) var(--ease-standard);
}

.profile-sidebar-nav-btn:hover {
  background: var(--surface);
}

.profile-sidebar-nav-btn:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

/* 激活态 —— 用 [aria-current="true"] 而不是 class（更 a11y 友好） */
.profile-sidebar-nav-btn[aria-current='true'] {
  background: color-mix(in oklab, var(--accent), transparent 90%);
  color: var(--accent);
  font-weight: 500;
}

/* ── 图标颜色独立（参考：muted 默认，accent 激活） ───────── */
.profile-sidebar-nav-icon {
  color: var(--muted);
  flex-shrink: 0;
  width: 16px;
  height: 16px;
}

.profile-sidebar-nav-btn[aria-current='true'] .profile-sidebar-nav-icon {
  color: var(--accent);
}

/* ── Label ─────────────────────────────────────────────────── */
.profile-sidebar-nav-label {
  flex: 1;
  text-align: left;
}

/* ── Badge（右上角徽标）── 参考的 .badge 样式 ────────────── */
.profile-sidebar-nav-badge {
  margin-left: auto;
  /* 复用 tokens.scss 的 .badge 基础样式，这里只重置 border + padding */
  border: none;
  padding: 2px 8px;
}

.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.badge-muted {
  color: var(--muted);
  background: color-mix(in oklab, var(--muted), transparent 88%);
}

.badge-info {
  color: var(--accent);
  background: color-mix(in oklab, var(--accent), transparent 90%);
}

.badge-success {
  color: var(--success);
  background: color-mix(in oklab, var(--success), transparent 90%);
}

.badge-warn {
  color: var(--warn);
  background: color-mix(in oklab, var(--warn), transparent 88%);
}

/* ── 工作区：次要导航，视觉稍弱（用 class 而非 :last-child） ─ */
.profile-sidebar-nav-btn--muted {
  opacity: 0.75;
}

.profile-sidebar-nav-btn--muted:hover {
  opacity: 1;
}
</style>
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
/* Sidebar 容器 ──────────────────────────────────────────── */
.profile-sidebar {
  /* 防止在 flex 布局中被拉伸，保持内容决定高度 */
  align-self: start;

  /* 上下内边距，确保内容与容器边缘保持舒适的呼吸空间 */
  padding-block: var(--space-8);

  /* 粘性定位：页面滚动时侧边栏固定在视口内，提升长页面操作体验 */
  position: sticky;

  /*
   * 固定位置计算：
   * var(--shell-topbar-h) = 顶部导航栏高度
   * var(--space-6) = 额外的顶部间距
   * 确保侧边栏不会被顶部导航栏遮挡，且保持视觉平衡
   */
  top: calc(var(--shell-topbar-h) + var(--space-6));
}

.sidebar-section {
  margin-bottom: var(--space-6);
}

.sidebar-label {
  padding-inline: var(--space-3);
  margin-bottom: var(--space-2);
}

/* 导航列表 ────────────────────────────────────────────── */
.nav-list {
  display: flex;
  flex-direction: column;

  /* 导航项之间的间距，紧凑密度让列表更凝聚，视觉上成组 */
  gap: 2px;
}

/* 导航按钮 ────────────────────────────────────────────── */
.profile-sidebar-nav-btn {
  /* 布局：图标 + 文字 + 徽章 水平排列，垂直居中 */
  display: flex;
  align-items: center;
  gap: var(--space-3);

  /* 尺寸与内边距：保证舒适的点击热区 */
  padding: 8px var(--space-3);
  width: 100%;

  /* 外观：纯净、扁平的导航项 */
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;

  /* 文字样式：清晰、内敛 */
  color: var(--fg);
  font-size: var(--text-sm);

  /* 常规字重，激活态才加粗 */
  font-weight: 400;
  text-align: left;
  text-decoration: none;

  /* 交互：点击反馈 */
  cursor: pointer;

  /* 动画：仅过渡背景色，避免不必要的重绘 */
  transition: background-color var(--motion-fast) var(--ease-standard);
}

/* 导航按钮悬停状态 */
.profile-sidebar-nav-btn:hover {
  background: var(--surface);
}

/* 聚焦可见状态 ──────────────────────────────────────────
 * 移除浏览器默认的轮廓线（因为 PrimeVue Button 默认有 outline），
 * 改用自定义的 focus-ring 阴影提供无障碍焦点指示。
 */
.profile-sidebar-nav-btn:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

/* 激活态 ──────────────────────────────────────────────
 * 激活判定走 [aria-current="true"] 而非 class，
 * 配合 PrimeVue Button unstyled 模式 + a11y 最佳实践。
 */
.profile-sidebar-nav-btn[aria-current='true'] {
  /* color-mix 是 CSS 新语法：用主色 90% 透明叠加，生成淡色背景 */
  background: color-mix(in oklab, var(--accent), transparent 90%);

  /* 文字颜色设为强调色，与背景形成视觉呼应 */
  color: var(--accent);

  /* 加粗字体，进一步突出当前选中项 */
  font-weight: 500;
}

/* 图标 ────────────────────────────────────────────────
 * 图标颜色独立于文字：默认用 muted（次要色）降低视觉权重，
 * 激活态切到 accent 与文字保持一致。
 */
.profile-sidebar-nav-icon {
  /* 默认柔和次要色，不抢导航文字风头 */
  color: var(--muted);

  /* 禁止图标被 flex 压缩，保持尺寸稳定 */
  flex-shrink: 0;

  /* 固定 16px，确保各导航项图标大小一致 */
  width: 16px;
  height: 16px;
}

.profile-sidebar-nav-btn[aria-current='true'] .profile-sidebar-nav-icon {
  /* 图标颜色同步切换为强调色，形成统一的视觉焦点 */
  color: var(--accent);
}

/* Label ─────────────────────────────────────────────── */
.profile-sidebar-nav-label {
  /* 占据剩余空间，将右侧的徽章推到最右 */
  flex: 1;
  text-align: left;
}

/* Badge ─────────────────────────────────────────────── */
.profile-sidebar-nav-badge {
  /* 推到最右，与导航标签拉开距离 */
  margin-left: auto;

  /* 显式无边框，统一外观（防止父级 Button unstyled 影响） */
  border: none;

  /* 微调内边距：上下 2px、左右 8px，使徽标更紧凑精致 */
  padding: 2px 8px;
}

/* 圆点徽标 —— 用于显示未读/新消息等状态指示 */
.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;

  /* 背景继承当前文字色，自动跟随父元素或激活态的强调色 */
  background: currentColor;
}

/* Badge 变体 ─────────────────────────────────────────
 * 4 个语义变体：muted=次要  info=提示  success=正向  warn=警示
 * 背景统一用 color-mix 派生，禁止硬编码色值，保持单一来源。
 */

/* 柔和灰徽标 —— 用于次要/默认状态提示 */
.badge-muted {
  /* 文字柔和次要色，降低视觉优先级 */
  color: var(--muted);

  /* 背景：柔和色 88% 透明叠加 */
  background: color-mix(in oklab, var(--muted), transparent 88%);
}

/* 信息/强调徽标 —— 用于主要提示 */
.badge-info {
  /* 文字使用主题强调色 */
  color: var(--accent);

  /* 背景：强调色 90% 透明叠加 */
  background: color-mix(in oklab, var(--accent), transparent 90%);
}

/* 成功徽标 —— 用于正向状态提示（已完成、通过） */
.badge-success {
  color: var(--success);

  /* 背景：成功色 90% 透明叠加 */
  background: color-mix(in oklab, var(--success), transparent 90%);
}

/* 警告徽标 —— 用于需要注意的状态提示（待处理、异常） */
.badge-warn {
  color: var(--warn);

  /* 背景：警告色 88% 透明叠加（略深于其他以提升警示感） */
  background: color-mix(in oklab, var(--warn), transparent 88%);
}

/* 次要导航按钮 ────────────────────────────────────────
 * 用于工作区/设置等低频入口，通过透明度弱化视觉权重，
 * 与主导航（账号）形成层级区分。
 */
.profile-sidebar-nav-btn--muted {
  /* 透明度降至 75%，弱于主导航项 */
  opacity: 0.75;
}

.profile-sidebar-nav-btn--muted:hover {
  /* 悬停时恢复完整可读性，避免误以为不可交互 */
  opacity: 1;
}
</style>
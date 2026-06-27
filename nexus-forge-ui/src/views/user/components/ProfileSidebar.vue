<template>
    <aside class="profile-sidebar" aria-label="账号设置">
        <div class="sidebar-section">
            <div class="sidebar-label eyebrow">账号</div>
            <nav class="nav-list" role="tablist" aria-orientation="vertical">
                <button
                    v-for="tab in accountTabs"
                    :key="tab.id"
                    class="nav-item"
                    role="tab"
                    :aria-current="active === tab.id ? 'true' : 'false'"
                    @click="$emit('switch', tab.id)"
                >
                    <component :is="tab.icon" class="icon nav-icon" />
                    {{ tab.label }}
                    <span
                        v-if="tab.badge"
                        class="nav-badge"
                        :class="['badge', `badge-${tab.badgeVariant ?? 'muted'}`]"
                    >
                        <span v-if="tab.badgeDot" class="badge-dot"></span>
                        {{ tab.badge }}
                    </span>
                </button>
            </nav>
        </div>

        <div class="sidebar-section">
            <div class="sidebar-label eyebrow">工作区</div>
            <nav class="nav-list" aria-label="工作区">
                <a v-for="link in workspaceLinks" :key="link.label" class="nav-item" href="#">
                    <component :is="link.icon" class="icon nav-icon" />
                    {{ link.label }}
                </a>
            </nav>
        </div>
    </aside>
</template>

<script setup lang="ts">
import { h } from 'vue'

defineProps<{
    active: string
}>()

defineEmits<{
    (e: 'switch', tab: string): void
}>()

// inline SVG icon factory —— 避免引入图标库
const icon = (path: string | string[]) =>
    () =>
        h(
            'svg',
            {
                class: 'icon nav-icon',
                viewBox: '0 0 24 24',
                fill: 'none',
                stroke: 'currentColor',
                'stroke-linecap': 'round',
                'stroke-linejoin': 'round',
                'aria-hidden': 'true',
            },
            (Array.isArray(path) ? path : [path]).map((d) => h('path', { d }))
        )

const accountTabs = [
    { id: 'profile', label: '基础资料', icon: icon(['M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2', 'M12 7a4 4 0 1 0 0-8 4 4 0 0 0 0 8z']) },
    { id: 'contact', label: '联系方式', icon: icon(['M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z', 'm22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7']) },
    { id: 'notifications', label: '通知与隐私', icon: icon(['M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9', 'M10.3 21a1.94 1.94 0 0 0 3.4 0']), badge: '3', badgeVariant: 'muted' },
    { id: 'security', label: '账号安全', icon: icon(['M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z', 'm9 12 2 2 4-4']), badge: '已开启', badgeVariant: 'success', badgeDot: true },
]

const workspaceLinks = [
    { label: '团队成员', icon: icon(['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'M9 7a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M22 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75']) },
    { label: '账单与订阅', icon: icon(['M2 5h20v14H2z', 'M2 10h20']) },
    { label: '集成与 API', icon: icon(['m16 18 6-6-6-6', 'm8 6-6 6 6 6']) },
]
</script>

<style scoped lang="scss">
.profile-sidebar {
    align-self: start;
    padding-block: var(--space-8);
    /* sticky 让 sidebar 跟随页面滚动保持可见 */
    position: sticky;
    top: calc(var(--shell-topbar-h) + var(--space-6));
}

.sidebar-section { margin-bottom: var(--space-6); }
.sidebar-label {
    padding-inline: var(--space-3);
    margin-bottom: var(--space-2);
}
</style>
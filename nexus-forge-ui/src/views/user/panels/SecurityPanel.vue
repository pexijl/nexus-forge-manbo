<template>
    <section class="panel" aria-labelledby="h-security">
        <div class="content-header">
            <h1 id="h-security">账号安全</h1>
            <p>保护账号免受未授权访问。建议每月检查一次。</p>
        </div>

        <article class="card section-card">
            <div class="card-header">
                <div class="card-title-block">
                    <h3 class="card-title">登录密码</h3>
                    <p class="card-desc">上次更新于 47 天前 (2026/05/11)</p>
                </div>
                <button class="btn btn-secondary btn-sm" type="button">更改密码</button>
            </div>

            <div class="form-grid">
                <div class="form-label">密码强度</div>
                <div class="form-control">
                    <div class="password-strength">
                        <div class="password-bar"><div class="password-bar-fill"></div></div>
                        <span class="body-sm password-label">强</span>
                    </div>
                    <p class="form-help">包含 14 个字符 · 大小写字母、数字和符号 · 未在已知泄漏列表中出现</p>
                </div>
            </div>
        </article>

        <article class="card section-card">
            <div class="card-header">
                <div class="card-title-block">
                    <h3 class="card-title">两步验证</h3>
                    <p class="card-desc">登录时除密码外需要第二因素验证。</p>
                </div>
                <span class="badge badge-success"><span class="badge-dot"></span>已开启</span>
            </div>

            <div class="toggle-row">
                <div class="toggle-text">
                    <strong>身份验证器 (TOTP)</strong>
                    <span>主方式 · 已绑定 1Password · 当前显示 6 位一次性密码</span>
                </div>
                <span class="body-sm body-muted last-active">2026/04/22</span>
                <button class="btn btn-ghost btn-sm" type="button">更换</button>
            </div>

            <div class="toggle-row">
                <div class="toggle-text">
                    <strong>短信验证 (备用)</strong>
                    <span>仅在 TOTP 不可用时启用,每月最多 5 次。</span>
                </div>
                <button class="toggle" role="switch" aria-checked="true" @click="on = !on"></button>
            </div>

            <div class="toggle-row">
                <div class="toggle-text">
                    <strong>安全密钥 (FIDO2)</strong>
                    <span>使用硬件密钥(如 YubiKey)。尚未注册设备。</span>
                </div>
                <button class="btn btn-secondary btn-sm" type="button">添加密钥</button>
            </div>
        </article>

        <article class="card section-card">
            <div class="card-header">
                <div class="card-title-block">
                    <h3 class="card-title">已登录设备</h3>
                    <p class="card-desc">当前共 3 个活跃会话。</p>
                </div>
                <button class="btn btn-danger btn-sm" type="button">注销其他设备</button>
            </div>

            <div v-for="d in devices" :key="d.name" class="device-row">
                <div class="icon-circle" aria-hidden="true" v-html="d.icon"></div>
                <div class="device-meta">
                    <div class="device-name">
                        {{ d.name }}
                        <span v-if="d.isCurrent" class="badge badge-success current-badge"><span class="badge-dot"></span>本机</span>
                    </div>
                    <div class="device-sub">{{ d.sub }}</div>
                </div>
                <button v-if="d.isCurrent" class="btn btn-ghost btn-sm" type="button" disabled style="visibility: hidden">注销</button>
                <button v-else class="btn btn-ghost btn-sm" type="button">注销</button>
            </div>
        </article>

        <article class="card section-card">
            <div class="card-header">
                <div class="card-title-block">
                    <h3 class="card-title">最近登录</h3>
                    <p class="card-desc">过去 30 天内 7 次登录,其中 1 次来自新设备。</p>
                </div>
            </div>

            <div v-for="log in loginLogs" :key="log.title" class="device-row">
                <div class="device-meta">
                    <div class="device-name">{{ log.title }}</div>
                    <div class="device-sub">{{ log.sub }}</div>
                </div>
                <span v-if="log.tag" class="badge" :class="`badge-${log.tagVariant}`">
                    <span v-if="log.tagDot" class="badge-dot"></span>{{ log.tag }}
                </span>
            </div>
        </article>
    </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const on = ref(true)

const devices = [
    {
        name: 'MacBook Pro 14" · Safari 17.2',
        sub: '杭州 · macOS 14.4 · 现在活动',
        isCurrent: true,
        icon: '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="14" x="2" y="5" rx="2"/><line x1="2" x2="22" y1="10" y2="10"/></svg>',
    },
    {
        name: 'iPhone 15 · 启明 App 4.2.1',
        sub: '杭州 · iOS 17.2 · 12 分钟前',
        isCurrent: false,
        icon: '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="2" width="14" height="20" rx="2"/><line x1="12" x2="12.01" y1="18" y2="18"/></svg>',
    },
    {
        name: 'MacBook Air · Chrome 121',
        sub: '北京 · macOS 13.6 · 2 天前',
        isCurrent: false,
        icon: '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="14" x="2" y="5" rx="2"/><line x1="2" x2="22" y1="10" y2="10"/></svg>',
    },
]

const loginLogs = [
    { title: '登录成功 · Safari 17.2 / 杭州', sub: '2026/06/27 09:14 · IP 36.x.x.x', tag: '本机', tagVariant: 'success', tagDot: true },
    { title: '登录成功 · 启明 App 4.2.1 / 杭州', sub: '2026/06/27 09:02 · 已批准' },
    { title: '新设备登录 · Chrome 121 / 北京', sub: '2026/06/25 22:41 · 通过 TOTP 验证', tag: '新设备', tagVariant: 'warn', tagDot: true },
    { title: '登录失败 · 密码错误 3 次', sub: '2026/06/24 16:08 · 已自动锁定 15 分钟', tag: '已阻止', tagVariant: 'muted' },
    { title: 'API 密钥轮换 · 1Password', sub: '2026/06/20 11:30 · 由你本人操作' },
]
</script>

<style scoped lang="scss">
.password-strength {
    display: flex;
    align-items: center;
    gap: var(--space-3);
}
.password-bar {
    flex: 1;
    height: 6px;
    border-radius: var(--radius-pill);
    background: var(--border);
    overflow: hidden;
}
.password-bar-fill {
    width: 75%;
    height: 100%;
    background: var(--success);
}
.password-label {
    font-weight: 500;
    color: var(--success);
}
.last-active {
    margin-right: var(--space-2);
    white-space: nowrap;
}
.current-badge {
    font-weight: 400;
}
</style>
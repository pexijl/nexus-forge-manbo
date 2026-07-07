<template>
  <section class="panel" aria-labelledby="h-contact">
    <div class="content-header">
      <h1 id="h-contact">联系方式</h1>
      <p>用于登录验证、账单通知和紧急安全提醒。</p>
    </div>

    <article class="card section-card">
      <div class="card-header">
        <div class="card-title-block">
          <h3 class="card-title">主要联系方式</h3>
          <p class="card-desc">每个项目至少保持一种已验证的方式。</p>
        </div>
      </div>

      <div class="form-grid">
        <div class="form-label">邮箱</div>
        <div class="form-control">
          <div class="contact-row">
            <svg
              class="icon icon-sm"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
              style="color: var(--muted)"
            >
              <rect width="20" height="16" x="2" y="4" rx="2" />
              <path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7" />
            </svg>
            <span class="body-sm mono contact-value">lin.wan@qiming.tech</span>
            <span class="badge badge-success"><span class="badge-dot"></span>已验证</span>
          </div>
          <div class="contact-actions">
            <button class="btn btn-ghost btn-sm" type="button">更换邮箱</button>
            <button class="btn btn-ghost btn-sm" type="button">关闭通知</button>
          </div>
        </div>

        <div class="form-label">手机</div>
        <div class="form-control">
          <div class="contact-row">
            <svg
              class="icon icon-sm"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
              style="color: var(--muted)"
            >
              <path
                d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.37 1.9.72 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.35 1.85.59 2.81.72A2 2 0 0 1 22 16.92z"
              />
            </svg>
            <span class="body-sm mono contact-value">+86 138 0013 8000</span>
            <span class="badge badge-success"><span class="badge-dot"></span>已验证</span>
          </div>
          <div class="contact-actions">
            <button class="btn btn-ghost btn-sm" type="button">更换手机</button>
            <button class="btn btn-ghost btn-sm" type="button">关闭通知</button>
          </div>
        </div>

        <label class="form-label" for="backup-email">备用邮箱</label>
        <div class="form-control">
          <input
            id="backup-email"
            type="email"
            placeholder="紧急情况下使用的备用邮箱"
            value="lin.wan@outlook.com"
          />
          <p class="form-help">仅用于账号恢复和紧急通知,不会出现在公开资料中。</p>
        </div>
      </div>

      <div class="save-bar">
        <button class="btn btn-ghost" type="button">放弃修改</button>
        <button class="btn btn-primary" type="button">保存修改</button>
      </div>
    </article>

    <article class="card section-card">
      <div class="card-header">
        <div class="card-title-block">
          <h3 class="card-title">第三方账号</h3>
          <p class="card-desc">使用第三方登录或快速分享资料到外部平台。</p>
        </div>
        <button class="btn btn-secondary btn-sm" type="button">
          <svg
            class="icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M5 12h14M12 5v14" />
          </svg>
          绑定更多
        </button>
      </div>

      <div v-for="provider in providers" :key="provider.name" class="connected-row">
        <div class="connected-icon" v-html="provider.icon"></div>
        <div class="connected-meta">
          <strong>{{ provider.name }}</strong>
          <span>{{ provider.desc }}</span>
        </div>
        <span v-if="provider.connected" class="badge badge-success">
          <span class="badge-dot"></span>已连接
        </span>
        <span v-else class="badge badge-muted">未连接</span>
        <button v-if="provider.connected" class="btn btn-ghost btn-sm" type="button">解绑</button>
        <button v-else class="btn btn-secondary btn-sm" type="button">连接</button>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
const providers = [
  {
    name: 'GitHub',
    desc: '@linwan · 2024 年 8 月绑定',
    connected: true,
    icon: '<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M12 .3a12 12 0 0 0-3.79 23.4c.6.11.82-.26.82-.58v-2c-3.34.73-4.04-1.61-4.04-1.61-.55-1.39-1.34-1.76-1.34-1.76-1.09-.74.08-.73.08-.73 1.21.08 1.85 1.24 1.85 1.24 1.07 1.83 2.81 1.3 3.5.99.11-.78.42-1.31.76-1.61-2.66-.3-5.47-1.33-5.47-5.93 0-1.31.47-2.38 1.24-3.22-.13-.3-.54-1.52.11-3.17 0 0 1.01-.32 3.3 1.23a11.5 11.5 0 0 1 6 0c2.29-1.55 3.3-1.23 3.3-1.23.65 1.65.24 2.87.12 3.17.77.84 1.23 1.91 1.23 3.22 0 4.61-2.81 5.62-5.49 5.92.43.37.81 1.1.81 2.22v3.29c0 .32.22.7.83.58A12 12 0 0 0 12 .3z"/></svg>',
  },
  {
    name: 'Apple',
    desc: '隐藏邮箱 · 2024 年 5 月绑定',
    connected: true,
    icon: '<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M17.05 12.62a3.7 3.7 0 0 1 1.74-3.1 3.74 3.74 0 0 0-2.94-1.59c-1.24-.13-2.43.73-3.06.73-.63 0-1.6-.71-2.63-.7A3.92 3.92 0 0 0 6.8 10.5c-1.4 2.43-.36 6.03 1.01 8 .67.97 1.47 2.05 2.5 2.01 1-.04 1.39-.65 2.6-.65 1.22 0 1.55.65 2.62.63 1.08-.02 1.77-.98 2.43-1.95a8.66 8.66 0 0 0 1.1-2.27 3.59 3.59 0 0 1-2.01-3.65zM14.96 6.18a3.55 3.55 0 0 0 .83-2.57 3.66 3.66 0 0 0-2.36 1.23 3.42 3.42 0 0 0-.85 2.49 3.04 3.04 0 0 0 2.38-1.15z"/></svg>',
  },
  {
    name: '微信',
    desc: '扫码登录已启用',
    connected: false,
    icon: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>',
  },
  {
    name: 'X (Twitter)',
    desc: '分享动态到 X',
    connected: false,
    icon: '<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z"/></svg>',
  },
];
</script>

<style scoped lang="scss">
.contact-value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}
.contact-actions {
  display: flex;
  gap: var(--space-2);
  margin-top: 8px;
}
</style>

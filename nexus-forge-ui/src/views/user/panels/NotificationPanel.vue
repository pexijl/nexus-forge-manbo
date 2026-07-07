<template>
  <section class="panel" aria-labelledby="h-notifications">
    <div class="content-header">
      <h1 id="h-notifications">通知与隐私</h1>
      <p>决定你希望接收的通知类型,以及谁可以在工作区之外看到你的信息。</p>
    </div>

    <article class="card section-card">
      <div class="card-header">
        <div class="card-title-block">
          <h3 class="card-title">通知偏好</h3>
          <p class="card-desc">每类事件都可以单独选择接收渠道。</p>
        </div>
      </div>

      <div class="channel-matrix-head">
        <span class="body-sm body-muted">事件</span>
        <span class="body-sm body-muted">邮件</span>
        <span class="body-sm body-muted">推送</span>
        <span class="body-sm body-muted">站内</span>
      </div>

      <div v-for="row in matrix" :key="row.label" class="channel-row">
        <span class="channel-label">{{ row.label }}</span>
        <div class="channel-group">
          <label><input type="checkbox" :checked="row.email" /></label>
          <label><input type="checkbox" :checked="row.push" /></label>
          <label><input type="checkbox" :checked="row.inApp" /></label>
        </div>
      </div>
    </article>

    <article class="card section-card">
      <div class="card-header">
        <div class="card-title-block">
          <h3 class="card-title">免打扰</h3>
          <p class="card-desc">暂停所有非紧急通知。</p>
        </div>
      </div>

      <div v-for="dnd in dndList" :key="dnd.title" class="toggle-row">
        <div class="toggle-text">
          <strong>{{ dnd.title }}</strong>
          <span>{{ dnd.desc }}</span>
        </div>
        <button
          class="toggle"
          role="switch"
          :aria-checked="dnd.on"
          @click="dnd.on = !dnd.on"
        ></button>
      </div>
    </article>

    <article class="card section-card">
      <div class="card-header">
        <div class="card-title-block">
          <h3 class="card-title">隐私</h3>
          <p class="card-desc">控制你的资料在工作区之外的可见性。</p>
        </div>
      </div>

      <div v-for="p in privacy" :key="p.title" class="toggle-row">
        <div class="toggle-text">
          <strong>{{ p.title }}</strong>
          <span>{{ p.desc }}</span>
        </div>
        <button class="toggle" role="switch" :aria-checked="p.on" @click="p.on = !p.on"></button>
      </div>
    </article>

    <article class="card section-card">
      <div class="card-header">
        <div class="card-title-block">
          <h3 class="card-title">数据导出</h3>
          <p class="card-desc">下载与你账号相关的所有数据。</p>
        </div>
      </div>
      <div class="row-between export-row">
        <p class="body-sm body-muted export-desc">
          将以 JSON
          格式打包你创建的项目、评论、上传的文件与设置项。文件准备完成后会通过站内信通知,链接 7
          天内有效。
        </p>
        <button class="btn btn-secondary" type="button">
          <svg
            class="icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
            <polyline points="7 10 12 15 17 10" />
            <line x1="12" x2="12" y1="15" y2="3" />
          </svg>
          申请数据导出
        </button>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { reactive } from 'vue';

const matrix = reactive([
  { label: '@ 提及与评论', email: true, push: true, inApp: true },
  { label: '设计稿评审请求', email: true, push: true, inApp: true },
  { label: '版本发布与公告', email: true, push: false, inApp: true },
  { label: '每周产品周报', email: true, push: false, inApp: false },
  { label: '活动邀请与营销内容', email: false, push: false, inApp: false },
]);

const dndList = reactive([
  {
    title: '工作时间外免打扰',
    desc: '每天 22:00 至次日 08:00 暂停推送与邮件,仅保留账号安全提醒。',
    on: true,
  },
  { title: '会议模式', desc: '日历显示"会议中"时自动暂停,直到会议结束。', on: false },
]);

const privacy = reactive([
  {
    title: '公开资料显示在团队成员列表',
    desc: '关闭后,只有你加入的工作区管理员能查看你的简介。',
    on: true,
  },
  {
    title: '允许搜索引擎索引个人主页',
    desc: '关闭后,搜索引擎不会收录 qiming.tech/linwan 页面。',
    on: true,
  },
  {
    title: '用 AI 改进产品体验',
    desc: '允许我们将匿名交互数据用于训练和优化推荐模型。可随时撤销。',
    on: false,
  },
]);
</script>

<style scoped lang="scss">
.export-row {
  align-items: flex-start;
  gap: var(--space-4);
  flex-wrap: wrap;
}
.export-desc {
  flex: 1;
  min-width: 240px;
  max-width: 56ch;
}
</style>

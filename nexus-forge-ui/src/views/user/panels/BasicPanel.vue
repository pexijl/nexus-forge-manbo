<template>
  <section class="panel is-active" aria-labelledby="h-profile">
    <div class="content-header">
      <h1 id="h-profile">基础资料</h1>
      <p>这些信息会出现在你的公开资料页和团队成员列表中。</p>
    </div>

    <article class="card section-card avatar-card">
      <AvatarUploader :avatar-url="avatarUrl" @change="onAvatarChange" />
      <div class="avatar-meta">
        <div class="avatar-name">{{ authStore.userInfo?.nickname }}</div>
        <div class="avatar-location">注册于 {{ formatDate(authStore.userInfo?.createdAt) }}</div>
      </div>
      <div class="avatar-actions">
        <Button label="更换头像" severity="primary" variant="text" size="small" />
        <Button label="移除头像" severity="danger" variant="text" size="small" />
      </div>
    </article>

    <article class="card section-card">
      <div class="card-header">
        <div class="card-title-block">
          <h3 class="card-title">公开资料</h3>
          <p class="card-desc">其他人查看到的姓名、简介和链接。</p>
        </div>
      </div>

      <div class="form-grid">
        <label class="form-label" for="display-name">显示姓名</label>
        <div class="form-control">
          <input id="display-name" type="text" value="林晚" autocomplete="name" />
          <p class="form-help">同事在 @ 提及和评论里会看到这个名字。</p>
        </div>

        <label class="form-label" for="handle">用户名</label>
        <div class="form-control">
          <div class="handle-input">
            <span class="handle-prefix">qiming.tech/</span>
            <input
              id="handle"
              class="handle-main"
              type="text"
              value="@linwan"
              autocomplete="username"
            />
          </div>
          <p class="form-help">修改后旧链接会在 90 天内通过 301 重定向到新地址。</p>
        </div>

        <label class="form-label" for="bio">个人简介</label>
        <div class="form-control">
          <textarea id="bio" maxlength="160">
设计研究员 · 关注人机交互与教育科技。 启明科技用户体验小组。</textarea>
          <p class="form-help">
            <span>{{ bioLen }}</span> / 160
          </p>
        </div>

        <label class="form-label" for="website">个人主页</label>
        <div class="form-control">
          <input id="website" type="url" placeholder="https://" value="https://linwan.design" />
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
          <h3 class="card-title">个人详情</h3>
          <p class="card-desc">仅自己和 HR 可见,用于合规与个性化推荐。</p>
        </div>
      </div>

      <div class="form-grid">
        <label class="form-label" for="legal-name">法定姓名</label>
        <div class="form-control">
          <input id="legal-name" type="text" value="林晚" autocomplete="name" />
        </div>

        <label class="form-label" for="gender">性别</label>
        <div class="form-control">
          <select id="gender">
            <option>女</option>
            <option>男</option>
            <option>其他</option>
            <option>不愿透露</option>
          </select>
        </div>

        <label class="form-label" for="birthday">生日</label>
        <div class="form-control">
          <input id="birthday" type="date" value="1997-04-12" />
        </div>

        <label class="form-label" for="location">所在地</label>
        <div class="form-control">
          <input id="location" type="text" value="杭州 · 中国" />
        </div>

        <label class="form-label" for="locale">语言</label>
        <div class="form-control">
          <select id="locale">
            <option>简体中文</option>
            <option>繁體中文</option>
            <option>English</option>
            <option>日本語</option>
          </select>
        </div>

        <label class="form-label" for="timezone">时区</label>
        <div class="form-control">
          <select id="timezone">
            <option>(GMT+08:00) 上海、北京、香港、新加坡</option>
            <option>(GMT+09:00) 东京、首尔</option>
            <option>(GMT+00:00) 伦敦、都柏林</option>
            <option>(GMT-08:00) 太平洋时间(洛杉矶)</option>
          </select>
        </div>
      </div>

      <div class="save-bar">
        <button class="btn btn-ghost" type="button">放弃修改</button>
        <button class="btn btn-primary" type="button">保存修改</button>
      </div>
    </article>

    <article class="card section-card danger-card">
      <div class="card-header">
        <div class="card-title-block">
          <h3 class="card-title">注销账号</h3>
          <p class="card-desc">永久删除账号和所有数据,30 天内可撤销。</p>
        </div>
      </div>
      <div class="row-between danger-row">
        <p class="body-sm body-muted danger-desc">
          注销后会从所有工作区中移除身份,公开资料将变成 404。你仍会保留已开发票的访问权限。
        </p>
        <button class="btn btn-danger" type="button">申请注销账号</button>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import AvatarUploader from '@/components/AvatarUploader.vue';
import { apiUploadAvatar } from '@/api/user';
import { useAuthStore } from '@/stores/auth';
import { useDateFormat } from '@/composables/useDateFormat';
const { formatDate } = useDateFormat();

const authStore = useAuthStore();
const bio = ref('设计研究员 · 关注人机交互与教育科技。 启明科技用户体验小组。');
const bioLen = computed(() => bio.value.length);

const avatarUrl = computed(() => authStore.userInfo?.avatarUrl);

const onAvatarChange = async (file: File) => {
  const userInfo = await apiUploadAvatar(file);
  authStore.userInfo = userInfo; // 写回 store，全局同步
};
</script>

<style scoped lang="scss">
.avatar-card {
  /* 启用 Grid 布局 */
  display: grid;

  /* 三列：左 | 中 | 右 */
  /* auto  = 自适应内容宽度（如头像大小）
     1fr   = 剩余空间全给中间（如用户名、描述）
     auto  = 自适应内容宽度（如操作按钮） */
  grid-template-columns: auto 1fr auto;

  /* 列间距：使用 CSS 变量，通常是 1rem/16px */
  gap: var(--space-4);

  /* 垂直居中对齐三列 */
  align-items: center;

  /* 内边距：上下 var(--space-4)，左右 var(--space-6) */
  padding: var(--space-4) var(--space-6);
}

.avatar-card .avatar-meta {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-width: 0;
}

.avatar-card .avatar-name {
  font-size: var(--text-base);
  font-weight: 600;
}

.avatar-card .avatar-location {
  font-size: var(--text-sm);
  color: var(--muted);
}

.avatar-card .avatar-actions {
  display: grid;
  grid-auto-flow: column;
  gap: var(--space-2);
}

.handle-input {
  display: flex;
  align-items: stretch;
  gap: 0;
}
.handle-prefix {
  display: inline-flex;
  align-items: center;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-right: none;
  border-radius: var(--radius-sm) 0 0 var(--radius-sm);
  background: var(--bg);
  color: var(--muted);
  font-size: var(--text-sm);
  font-family: var(--font-mono);
}
.handle-main {
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0 !important;
  border-left: none !important;
}
.danger-row {
  align-items: flex-start;
  gap: var(--space-4);
  flex-wrap: wrap;
}
.danger-desc {
  flex: 1;
  min-width: 240px;
  max-width: 56ch;
}
</style>

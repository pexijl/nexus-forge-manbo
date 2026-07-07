<template>
  <section class="panel is-active" aria-labelledby="h-profile">
    <div class="content-header">
      <h1 id="h-profile">基础资料</h1>
    </div>

    <article class="card section-card avatar-card">
      <AvatarUploader
        ref="avatarRef"
        :avatar-url="avatarUrl"
        :loading="uploading"
        @change="onAvatarChange"
      />
      <div class="avatar-meta">
        <div class="avatar-name">{{ authStore.userInfo?.nickname }}</div>
        <div class="avatar-location">注册于 {{ formatDate(authStore.userInfo?.createdAt) }}</div>
      </div>
      <div class="avatar-actions">
        <Button label="更换头像" severity="primary" variant="text" @click="avatarRef?.open()" />
        <Button
          label="移除头像"
          severity="danger"
          variant="text"
          :disabled="!avatarUrl || removing"
          :loading="removing"
          @click="confirmRemoveAvatar"
        />
      </div>
    </article>

    <article class="card section-card">
      <div class="card-header">
        <div class="card-title-block">
          <h3 class="card-title">公开资料</h3>
          <p class="card-desc">其他人查看到的姓名、邮箱和手机号。</p>
        </div>
      </div>

      <div class="form-grid">
        <!-- 显示名称（昵称） -->
        <label class="form-label" for="display-name">显示名称</label>
        <div class="form-control">
          <InputText id="display-name" v-model="form.nickname" :invalid="!!errors.nickname" fluid />
          <p v-if="errors.nickname" class="form-help form-help--error">
            {{ errors.nickname }}
          </p>
          <p v-else class="form-help">同事在 @ 提及和评论里会看到这个名字。</p>
        </div>

        <!-- 用户名（只读） -->
        <label class="form-label" for="handle">用户名</label>
        <div class="form-control">
          <InputText id="handle" :model-value="form.username" disabled fluid />
          <p class="form-help">用户名用于登录，不可修改。</p>
        </div>

        <!-- 邮箱 -->
        <label class="form-label" for="email">邮箱</label>
        <div class="form-control">
          <InputText
            id="email"
            v-model="form.email"
            type="email"
            autocomplete="email"
            :invalid="!!errors.email"
            @blur="validateField('email')"
            fluid
          />
          <p v-if="errors.email" class="form-help form-help--error">
            {{ errors.email }}
          </p>
          <p v-else class="form-help">用于接收通知和找回账号。</p>
        </div>

        <!-- 手机号 -->
        <label class="form-label" for="phone">手机号</label>
        <div class="form-control">
          <InputText
            id="phone"
            v-model="form.phone"
            type="tel"
            autocomplete="tel"
            :invalid="!!errors.phone"
            @blur="validateField('phone')"
            placeholder="选填"
            fluid
          />
          <p v-if="errors.phone" class="form-help form-help--error">
            {{ errors.phone }}
          </p>
          <p v-else class="form-help">中国大陆手机号，11 位。</p>
        </div>
      </div>

      <div class="save-bar">
        <Button
          label="放弃修改"
          severity="secondary"
          variant="text"
          :disabled="!isDirty || saving"
          @click="resetForm"
        />
        <Button
          label="保存修改"
          :loading="saving"
          :disabled="!isDirty || saving"
          @click="saveProfile"
        />
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
import { ref, computed, watch } from 'vue';
import AvatarUploader from '@/components/AvatarUploader.vue';
import { apiRemoveAvatar, apiUpdateUserInfo, apiUploadAvatar } from '@/api/user';
import { useAuthStore } from '@/stores/auth';
import { useDateFormat } from '@/composables/useDateFormat';
import { useToast } from 'primevue/usetoast';
import { useConfirm } from 'primevue/useconfirm';
import { getErrorMessage } from '@/utils/error';
import { UserStatus, type UpdateUserInfo, type UserInfo } from '@/types/models/user';
const { formatDate } = useDateFormat();

const confirm = useConfirm();
const toast = useToast();
const authStore = useAuthStore();
const uploading = ref(false);
const removing = ref(false);
const avatarRef = ref<InstanceType<typeof AvatarUploader>>();

const form = ref<UserInfo>({
  id: 0,
  username: '',
  email: '',
  nickname: '',
  avatarUrl: '',
  phone: '',
  status: UserStatus.ACTIVE,
  roles: [],
  lastLoginAt: '',
  createdAt: '',
  updatedAt: '',
});
const initialForm = ref<UserInfo>({ ...form.value });
const errors = ref<Partial<Record<keyof UpdateUserInfo, string>>>({});
const saving = ref(false);

watch(
  () => authStore.userInfo?.id, // 只看 id 变化（首次加载）
  () => {
    if (authStore.userInfo) {
      form.value = { ...authStore.userInfo };
      initialForm.value = { ...authStore.userInfo };
    }
  },
  { immediate: true }
);
// 脏值检测
const isDirty = computed(() => {
  return (
    form.value.nickname !== initialForm.value.nickname ||
    form.value.email !== initialForm.value.email ||
    form.value.phone !== initialForm.value.phone
  );
});

// 字段校验（前端预校验，提交前拦一道）
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PHONE_RE = /^1[3-9]\d{9}$/;

function validateField(field: 'email' | 'phone' | 'nickname') {
  errors.value[field] = undefined;
  if (field === 'email' && form.value.email) {
    if (!EMAIL_RE.test(form.value.email)) {
      errors.value.email = '邮箱格式不正确';
    }
  }
  if (field === 'phone' && form.value.phone) {
    if (!PHONE_RE.test(form.value.phone)) {
      errors.value.phone = '手机号格式不正确';
    }
  }
  if (field === 'nickname' && form.value.nickname) {
    if (form.value.nickname.length > 20) {
      errors.value.nickname = '昵称不能超过 20 个字符';
    }
  }
}

async function saveProfile() {
  // 提交前全量校验
  validateField('nickname');
  validateField('email');
  validateField('phone');
  if (Object.values(errors.value).some(Boolean)) {
    toast.add({ severity: 'warn', summary: '请检查表单', group: 'br', life: 3000 });
    return;
  }

  saving.value = true;
  try {
    // 只提交变更的字段（PATCH 语义）
    const payload: UpdateUserInfo = {};
    if (form.value.nickname !== initialForm.value.nickname) payload.nickname = form.value.nickname;
    if (form.value.email !== initialForm.value.email) payload.email = form.value.email;
    if (form.value.phone !== initialForm.value.phone) payload.phone = form.value.phone;

    const userInfo = await apiUpdateUserInfo(payload);
    authStore.userInfo = userInfo; // 写回 store
    initialForm.value = { ...userInfo }; // 重置脏值基准
    toast.add({ severity: 'success', summary: '保存成功', group: 'br', life: 3000 });
  } catch (error) {
    toast.add({
      severity: 'error',
      summary: '保存失败',
      detail: getErrorMessage(error),
      group: 'br',
      life: 3000,
    });
  } finally {
    saving.value = false;
  }
}

function resetForm() {
  form.value = { ...initialForm.value };
  errors.value = {};
}

const avatarUrl = computed(() => authStore.userInfo?.avatarUrl);

const onAvatarChange = async (file: File) => {
  uploading.value = true;
  try {
    const userInfo = await apiUploadAvatar(file);
    authStore.userInfo = userInfo; // 写回 store，全局同步
    toast.add({ severity: 'success', summary: '头像上传成功', group: 'br', life: 3000 });
  } catch (error) {
    toast.add({
      severity: 'error',
      summary: '头像上传失败',
      detail: getErrorMessage(error),
      group: 'br',
      life: 3000,
    });
  } finally {
    uploading.value = false;
  }
};

const confirmRemoveAvatar = () => {
  confirm.require({
    group: 'positioned',
    message: '确定要移除当前头像吗？',
    header: '移除头像',
    icon: 'pi pi-exclamation-triangle',
    rejectProps: {
      label: '取消',
      severity: 'secondary',
    },
    acceptProps: {
      label: '确定',
      severity: 'danger',
    },
    accept: async () => {
      await doRemoveAvatar();
    },
  });
};

const doRemoveAvatar = async () => {
  removing.value = true;
  try {
    const userInfo = await apiRemoveAvatar();
    authStore.userInfo = userInfo; // avatarUrl=null 已写回 store
    toast.add({ severity: 'success', summary: '头像已移除', group: 'br', life: 3000 });
  } catch (error) {
    toast.add({
      severity: 'error',
      summary: '移除失败',
      detail: getErrorMessage(error),
      group: 'br',
      life: 3000,
    });
  } finally {
    removing.value = false;
  }
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
.form-help--error {
  color: var(--error, #ef4444);
}
</style>

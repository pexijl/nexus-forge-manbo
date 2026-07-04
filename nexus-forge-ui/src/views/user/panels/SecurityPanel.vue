<template>
  <section class="panel" aria-labelledby="h-security">
    <div class="content-header">
      <h1 id="h-security">账号安全</h1>
      <p>保护账号免受未授权访问。建议每月检查一次。</p>
    </div>

    <article class="card section-card">
      <!-- 当前密码状态 -->
      <div class="security-status">
        <div :class="`status-icon ${passwordStatus.level}`" id="statusIcon">
          <!-- 安全图标 -->
          <svg
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
          >
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
          </svg>
        </div>
        <div class="status-info">
          <div class="status-title">当前密码安全</div>
          <div class="status-desc" id="lastUpdated">
            上次更新于 {{ passwordStatus.daysAgo }} 天前 ({{ passwordStatus.lastUpdated }})
          </div>
        </div>
      </div>

      <!-- 强度指示器 -->
      <div class="strength-section">
        <div class="strength-header">
          <span class="strength-label">密码强度</span>
          <span :class="`strength-value ${passwordStatus.strengthLevel}`" id="currentStrength">
            {{ passwordStatus.strength }}
          </span>
        </div>
        <div class="strength-bars">
          <div
            v-for="(_, i) in passwordRules"
            :key="i"
            class="strength-bar"
            :class="i < passedCount ? 'active ' + passwordStatus.strengthLevel : ''"
          ></div>
        </div>
        <div class="strength-details" id="strengthDetails">
          {{ passwordStatus.detail }}
        </div>
      </div>
    </article>

    <!-- 修改密码表单 -->
    <article class="card section-card">
      <div class="card-header">
        <div class="card-title-block">
          <h3 class="card-title">设置新密码</h3>
          <p class="card-desc">建议使用 8 位以上，包含大小写字母、数字和符号</p>
        </div>
      </div>

      <!-- 旧密码 -->
      <div class="form-grid">
        <label for="oldPassword" class="form-label">当前密码</label>
        <IconField>
          <InputIcon>
            <Lock />
          </InputIcon>
          <InputPassword
            id="oldPassword"
            name="oldPassword"
            placeholder="请输入当前密码"
            v-model="oldPassword"
            :mask="oldPasswordMask"
          />
          <InputIcon class="cursor-pointer" @click="oldPasswordMask = !oldPasswordMask">
            <Eye v-if="oldPasswordMask" />
            <EyeSlash v-else />
          </InputIcon>
        </IconField>
        <div class="error-message" id="oldPasswordError">当前密码错误</div>
      </div>

      <!-- 新密码 -->
      <div class="form-grid">
        <label for="newPassword" class="form-label">新密码</label>
        <IconField>
          <InputIcon>
            <Lock />
          </InputIcon>
          <InputPassword
            id="newPassword"
            name="newPassword"
            placeholder="请输入新密码"
            v-model="newPassword"
            :mask="newPasswordMask"
            fluid
          />
          <InputIcon class="cursor-pointer" @click="newPasswordMask = !newPasswordMask">
            <Eye v-if="newPasswordMask" :size="16" />
            <EyeSlash v-else :size="16" />
          </InputIcon>
        </IconField>
        <div></div>
        <!-- 密码规则检查（Chip 模式） -->
        <div class="mt-3 flex flex-wrap items-center gap-1.5">
          <Chip
            v-for="rule in passwordRules"
            :key="rule.label"
            :class="
              'border-surface-200 dark:border-surface-700 gap-1.5! border bg-transparent! px-2! py-1! text-xs! ' +
              (rule.test(newPassword)
                ? 'text-green-600!'
                : 'text-surface-500! dark:text-surface-400!')
            "
          >
            <span
              :class="
                'inline-flex size-4 items-center justify-center rounded-full ' +
                (rule.test(newPassword)
                  ? 'bg-green-600 text-white! dark:bg-green-600 dark:text-black'
                  : 'bg-gray-200 text-gray-500! dark:bg-gray-300 dark:text-gray-400')
              "
            >
              <Check v-if="rule.test(newPassword)" :size="12" />
              <Times v-else :size="12" />
            </span>
            {{ rule.label }}
          </Chip>
        </div>
      </div>

      <!-- 确认密码 -->
      <div class="form-grid">
        <label for="confirmPassword" class="form-label">确认新密码</label>
        <IconField>
          <InputIcon>
            <Lock />
          </InputIcon>
          <InputPassword
            id="confirmPassword"
            name="confirmPassword"
            placeholder="请再次输入新密码"
            v-model="confirmPassword"
            :mask="confirmPasswordMask"
          />
          <InputIcon class="cursor-pointer" @click="confirmPasswordMask = !confirmPasswordMask">
            <Eye v-if="confirmPasswordMask" />
            <EyeSlash v-else />
          </InputIcon>
        </IconField>
        <div class="error-message" id="confirmError">两次输入的密码不一致</div>
      </div>

      <!-- 操作按钮 -->
      <div class="form-actions">
        <Button :disabled="!canSubmit" :loading="submitting" @click="onUpdatePassword" size="small">
          确认修改
        </Button>
        <Button severity="secondary" variant="text" size="small" @click="onCancel">取消</Button>
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
            <span v-if="d.isCurrent" class="badge badge-success current-badge"
              ><span class="badge-dot"></span>本机</span
            >
          </div>
          <div class="device-sub">{{ d.sub }}</div>
        </div>
        <button
          v-if="d.isCurrent"
          class="btn btn-ghost btn-sm"
          type="button"
          disabled
          style="visibility: hidden"
        >
          注销
        </button>
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
import Check from '@primeicons/vue/check';
import Times from '@primeicons/vue/times';
import Lock from '@primeicons/vue/lock';
import Chip from 'primevue/chip';
import Eye from '@primeicons/vue/eye';
import EyeSlash from '@primeicons/vue/eye-slash';
import InputPassword from 'primevue/inputpassword';
import { computed, ref } from 'vue';
import { apiUpdatePassword } from '@/api/user';
import { useToast } from 'primevue/usetoast';
import { getErrorMessage } from '@/utils/error';

const toast = useToast();

const on = ref(true);

const oldPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const oldPasswordMask = ref(true);
const newPasswordMask = ref(true);
const confirmPasswordMask = ref(true);
const submitting = ref(false);

const passwordRules = [
  { label: '至少 8 个字符', test: (v: string) => v.length >= 8 },
  // { label: '包含大写字母', test: (v: string) => /[A-Z]/.test(v) },
  { label: '包含小写字母', test: (v: string) => /[a-z]/.test(v) },
  // { label: '包含数字', test: (v: string) => /\d/.test(v) },
  // { label: '包含特殊符号', test: (v: string) => /[^a-zA-Z0-9]/.test(v) },
];

const allRulesPass = computed(() => passwordRules.every((r) => r.test(newPassword.value)));
const passwordMatch = computed(
  () => newPassword.value.length > 0 && newPassword.value === confirmPassword.value
);
const oldPasswordFilled = computed(() => oldPassword.value.length > 0);
const canSubmit = computed(
  () => !submitting.value && allRulesPass.value && passwordMatch.value && oldPasswordFilled.value
);
const passedCount = computed(() => passwordRules.filter((r) => r.test(newPassword.value)).length);

const passwordStatus = ref({
  level: 'safe' as 'safe' | 'warning' | 'danger',
  lastUpdated: '2026-05-11',
  daysAgo: 47,
  strength: '强',
  strengthLevel: 'strong' as 'weak' | 'fair' | 'good' | 'strong',
  detail: '包含 14 个字符 · 大小写字母、数字和符号 · 未在已知泄露列表中出现',
});

const resetPasswordForm = () => {
  oldPassword.value = '';
  newPassword.value = '';
  confirmPassword.value = '';
};

const onUpdatePassword = async () => {
  submitting.value = true;
  try {
    await apiUpdatePassword({
      oldPassword: oldPassword.value,
      newPassword: newPassword.value,
    });
    // 成功后清空表单
    resetPasswordForm();
    // 刷新状态卡
    passwordStatus.value = {
      ...passwordStatus.value,
      daysAgo: 0,
      lastUpdated: new Date().toISOString().slice(0, 10),
    };
    toast.add({ severity: 'success', summary: '密码更新成功', group: 'br', life: 3000 });
  } catch (error) {
    toast.add({
      severity: 'error',
      summary: '保存失败',
      detail: getErrorMessage(error),
      group: 'br',
      life: 3000,
    });
  } finally {
    submitting.value = false;
  }
};

const onCancel = () => {
  resetPasswordForm();
};

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
];

const loginLogs = [
  {
    title: '登录成功 · Safari 17.2 / 杭州',
    sub: '2026/06/27 09:14 · IP 36.x.x.x',
    tag: '本机',
    tagVariant: 'success',
    tagDot: true,
  },
  { title: '登录成功 · 启明 App 4.2.1 / 杭州', sub: '2026/06/27 09:02 · 已批准' },
  {
    title: '新设备登录 · Chrome 121 / 北京',
    sub: '2026/06/25 22:41 · 通过 TOTP 验证',
    tag: '新设备',
    tagVariant: 'warn',
    tagDot: true,
  },
  {
    title: '登录失败 · 密码错误 3 次',
    sub: '2026/06/24 16:08 · 已自动锁定 15 分钟',
    tag: '已阻止',
    tagVariant: 'muted',
  },
  { title: 'API 密钥轮换 · 1Password', sub: '2026/06/20 11:30 · 由你本人操作' },
];
</script>

<style scoped lang="scss">
.last-active {
  margin-right: var(--space-2);
  white-space: nowrap;
}
.current-badge {
  font-weight: 400;
}

/* 当前密码状态卡片 */
.security-status {
  display: flex;
  align-items: center;
  gap: 16px;
}

.status-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.status-icon.safe {
  background: #f0f9eb;
  color: #67c23a;
}

.status-icon.warning {
  background: #fdf6ec;
  color: #e6a23c;
}

.status-icon.danger {
  background: #fef0f0;
  color: #f56c6c;
}

.status-info {
  flex: 1;
}

.status-title {
  font-size: 16px;
  font-weight: 500;
  margin-bottom: 4px;
}

.status-desc {
  font-size: 13px;
  color: #8c8c8c;
}

/* 强度指示器 */
.strength-section {
  padding-top: 16px;
  border-top: 1px solid #f0f0f0;
}

.strength-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.strength-label {
  font-size: 14px;
  color: #595959;
}

.strength-value {
  font-size: 14px;
  font-weight: 600;
}

.strength-value.strong {
  color: #67c23a;
}

.strength-value.good {
  color: #409eff;
}

.strength-value.fair {
  color: #e6a23c;
}

.strength-value.weak {
  color: #f56c6c;
}

/* 分段进度条 */
.strength-bars {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
}

.strength-bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: #e8e8e8;
  transition: all 0.3s ease;
}

.strength-bar.active.weak {
  background: #f56c6c;
}

.strength-bar.active.fair {
  background: #e6a23c;
}

.strength-bar.active.good {
  background: #409eff;
}

.strength-bar.active.strong {
  background: #67c23a;
}

.strength-details {
  font-size: 13px;
  color: #8c8c8c;
  line-height: 1.8;
}

.form-input {
  width: 100%;
  height: 44px;
  padding: 0 44px 0 16px;
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  font-size: 15px;
  color: #1a1a1a;
  transition: all 0.2s;
  background: #fff;
}

.form-input:hover {
  border-color: #bfbfbf;
}

.form-input:focus {
  outline: none;
  border-color: #409eff;
  box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.1);
}

.form-input.error {
  border-color: #f56c6c;
  box-shadow: 0 0 0 3px rgba(245, 108, 108, 0.1);
}

.form-input.success {
  border-color: #67c23a;
  box-shadow: 0 0 0 3px rgba(103, 194, 58, 0.1);
}

.toggle-password {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8c8c8c;
  border-radius: 6px;
  transition: all 0.2s;
}

.toggle-password:hover {
  background: #f5f5f5;
  color: #595959;
}

.error-message {
  font-size: 13px;
  color: #f56c6c;
  margin-top: 6px;
  display: none;
}

.error-message.show {
  display: block;
}

/* 操作按钮 */
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
}

.hint-text {
  font-size: 13px;
  color: #8c8c8c;
  margin-top: 6px;
}

/* 新密码强度实时预览 */
.live-strength {
  margin-top: 12px;
  padding: 12px 16px;
  background: #fafafa;
  border-radius: 8px;
  display: none;
}

.live-strength.show {
  display: block;
}

.live-strength-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.live-strength-label {
  font-size: 13px;
  color: #595959;
}

.live-strength-value {
  font-size: 13px;
  font-weight: 600;
}

.live-strength-bars {
  display: flex;
  gap: 4px;
}

.live-strength-bar {
  flex: 1;
  height: 4px;
  border-radius: 2px;
  background: #e8e8e8;
  transition: all 0.3s;
}

/* 密码规则列表 */
.password-rules {
  margin-top: 16px;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
}

.rule-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: #8c8c8c;
  margin-bottom: 10px;
  transition: all 0.3s;
}

.rule-item:last-child {
  margin-bottom: 0;
}

.rule-item.valid {
  color: #67c23a;
}

.rule-icon {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  flex-shrink: 0;
  background: #e8e8e8;
  color: #bfbfbf;
  transition: all 0.3s;
}

.rule-item.valid .rule-icon {
  background: #f0f9eb;
  color: #67c23a;
}
</style>

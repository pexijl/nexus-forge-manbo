<template>
  <Form
    class="login-form-container"
    v-slot="$form"
    :initialValues="initialValues"
    :resolver="resolver"
    @submit="handleLogin"
  >
    <h2 class="text-center text-2xl font-bold">登录</h2>
    <div class="form-field">
      <FloatLabel>
        <IconField>
          <InputIcon>
            <User />
          </InputIcon>
          <InputText id="account" name="account" v-model="loginForm.account" fluid />
        </IconField>
        <label for="account">用户名或邮箱</label>
      </FloatLabel>
      <Message v-if="$form.account?.invalid" severity="error" size="small" variant="simple">
        {{ $form.account.error?.message }}
      </Message>
    </div>
    <div class="form-field">
      <FloatLabel>
        <IconField>
          <InputIcon>
            <Lock />
          </InputIcon>
          <InputPassword
            id="password"
            name="password"
            v-model="loginForm.password"
            :mask="passwordMask"
            fluid
          />
          <InputIcon class="cursor-pointer" @click="passwordMask = !passwordMask">
            <Eye v-if="passwordMask" />
            <EyeSlash v-else />
          </InputIcon>
        </IconField>
        <label for="password">密码</label>
      </FloatLabel>
      <Message v-if="$form.password?.invalid" severity="error" size="small" variant="simple">
        {{ $form.password.error?.message }}
      </Message>
    </div>
    <Button type="submit" label="登录" />
    <p class="auth-switch">
      还没有账号？
      <a @click="switchToRegister">去注册</a>
    </p>
  </Form>
</template>

<script setup lang="ts">
import User from '@primeicons/vue/user';
import Lock from '@primeicons/vue/lock';
import Eye from '@primeicons/vue/eye';
import EyeSlash from '@primeicons/vue/eye-slash';
import InputPassword from 'primevue/inputpassword';
import { zodResolver } from '@primevue/forms/resolvers/zod';
import { z } from 'zod';
import type { LoginRequest } from '@/types/auth';
import { useAuthStore } from '@/stores/auth';
import { ref } from 'vue';
import { useToast } from 'primevue/usetoast';
import { getErrorMessage } from '@/utils/error';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const toast = useToast();
const authStore = useAuthStore();
const passwordMask = ref(true);

const initialValues = ref<LoginRequest>({
  account: '',
  password: '',
});

const loginForm = ref<LoginRequest>({
  account: '',
  password: '',
});

const schema = z.object({
  account: z.string().min(1, { message: '用户名或邮箱不能为空' }),
  password: z.string().min(1, { message: '密码不能为空' }),
});

const resolver = ref(zodResolver(schema));

interface FormSubmitEvent {
  valid: boolean;
  values: Record<string, any>;
  states: Record<string, any>;
}

const handleLogin = async ({ valid, values }: FormSubmitEvent) => {
  if (!valid) return;
  try {
    await authStore.login(values as LoginRequest);
    toast.add({ severity: 'success', summary: '登录成功', life: 3000 });
    const raw = route.query.redirect;
    const candidate = Array.isArray(raw) ? raw[0] : raw;
    const safe = candidate && /^\/(?!\/)/.test(candidate) ? candidate : '/';
    router.replace(safe); // 使用 replace 避免登录后返回到登录页
  } catch (error) {
    toast.add({
      severity: 'error',
      summary: '登录失败',
      detail: getErrorMessage(error),
      life: 3000,
    });
  }
};
const switchToRegister = () => {
  router.push({ query: { tab: 'register' } });
};
</script>

<style scoped lang="scss">
.login-form-container {
  width: 100%;
  max-width: 420px;
  min-height: 400px;
  padding: 2rem;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.form-field {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-bottom: 1rem;
}

.auth-switch {
  text-align: center;
  color: var(--text-secondary);
  font-size: 14px;

  a {
    color: var(--primary-500);
    cursor: pointer;
    font-weight: 500;

    &:hover {
      text-decoration: underline;
    }
  }
}
</style>

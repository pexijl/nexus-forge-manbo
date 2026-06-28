<template>
  <Form
    class="register-form-container"
    v-slot="$form"
    :initialValues
    :resolver="resolver"
    @submit="handleRegister"
  >
    <h2 class="text-center text-2xl font-bold">注册</h2>

    <div class="form-field">
      <FloatLabel>
        <InputText name="username" id="username" v-model="registerForm.username" fluid />
        <label for="username">用户名</label>
      </FloatLabel>
      <Message v-if="$form.username?.invalid" severity="error" size="small" variant="simple">
        {{ $form.username.error?.message }}
      </Message>
    </div>

    <div class="form-field">
      <FloatLabel>
        <InputText name="email" id="email" v-model="registerForm.email" type="email" fluid />
        <label for="email">邮箱</label>
      </FloatLabel>
      <Message v-if="$form.email?.invalid" severity="error" size="small" variant="simple">
        {{ $form.email.error?.message }}
      </Message>
    </div>

    <div class="form-field">
      <FloatLabel>
        <InputText
          name="password"
          id="password"
          v-model="registerForm.password"
          type="password"
          fluid
        />
        <label for="password">密码</label>
      </FloatLabel>
      <Message v-if="$form.password?.invalid" severity="error" size="small" variant="simple">
        {{ $form.password.error?.message }}
      </Message>
    </div>

    <div class="form-field">
      <FloatLabel>
        <InputText
          name="confirmPassword"
          id="confirmPassword"
          v-model="registerForm.confirmPassword"
          type="password"
          fluid
        />
        <label for="confirmPassword">确认密码</label>
      </FloatLabel>
      <Message v-if="$form.confirmPassword?.invalid" severity="error" size="small" variant="simple">
        {{ $form.confirmPassword.error?.message }}
      </Message>
    </div>

    <Button type="submit" label="注册" />

    <p class="auth-switch">
      已有账号？
      <a @click="switchToLogin">去登录</a>
    </p>
  </Form>
</template>

<script setup lang="ts">
import { zodResolver } from '@primevue/forms/resolvers/zod';
import { z } from 'zod';
import router from '@/router';
import { useAuthStore } from '@/stores/auth';
import { ref } from 'vue';
import { useToast } from 'primevue/usetoast';

const toast = useToast();
const authStore = useAuthStore();

const initialValues = ref({
  username: '',
  email: '',
  password: '',
  confirmPassword: '',
});

const registerForm = ref({
  username: '',
  email: '',
  password: '',
  confirmPassword: '',
});

const schema = z
  .object({
    username: z.string().min(3, { message: '用户名至少3位' }),
    email: z.email({ message: '邮箱格式不正确' }),
    password: z.string().min(6, { message: '密码至少6位' }),
    confirmPassword: z.string(),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: '两次密码不一致',
    path: ['confirmPassword'],
  });

const resolver = ref(zodResolver(schema));

interface FormSubmitEvent {
  valid: boolean;
  values: Record<string, any>;
  states: Record<string, any>;
}

const handleRegister = async ({ valid, values }: FormSubmitEvent) => {
  if (valid) {
    try {
      await authStore.register({
        username: registerForm.value.username,
        email: registerForm.value.email,
        password: registerForm.value.password,
      });
      toast.add({ severity: 'success', summary: '注册成功', group: 'br', life: 3000 });
      router.push({ query: { tab: 'login' } });
    } catch (error) {
      let errMsg = '注册失败，请重试';
      if (error instanceof Error) {
        errMsg = error.message;
      } else if (typeof error === 'string') {
        errMsg = error;
      }
      toast.add({
        severity: 'error',
        summary: '注册失败',
        detail: errMsg,
        group: 'br',
        life: 3000,
      });
    } finally {
    }
  }
};

const switchToLogin = () => {
  router.push({ query: { tab: 'login' } });
};
</script>

<style scoped lang="scss">
.register-form-container {
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

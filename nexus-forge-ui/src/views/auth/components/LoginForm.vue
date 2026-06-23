<template>
    <Form class="login-form-container" v-slot="$form" :initialValues="initialValues" :resolver="resolver"
        @submit="handleLogin">
        <h2 class="font-bold text-2xl text-center">登录</h2>
        <div class="form-field">
            <FloatLabel>
                <InputText name="account" id="account" v-model="loginForm.account" fluid />
                <label for="account">用户名或邮箱</label>
            </FloatLabel>
            <Message v-if="$form.account?.invalid" severity="error" size="small" variant="simple">
                {{ $form.account.error?.message }}
            </Message>
        </div>
        <div class="form-field">
            <FloatLabel>
                <InputText name="password" id="password" v-model="loginForm.password" type="password" fluid />
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
import { zodResolver } from '@primevue/forms/resolvers/zod';
import { z } from 'zod'
import router from '@/router'
import type { LoginRequest } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { ref } from 'vue'
import { useToast } from 'primevue/usetoast'

const toast = useToast()
const authStore = useAuthStore()

const initialValues = ref<LoginRequest>({
    account: '',
    password: ''
})

const loginForm = ref<LoginRequest>({
    account: '',
    password: ''
})

const schema = z.object({
    account: z.string().min(1, { message: '用户名或邮箱不能为空' }),
    password: z.string().min(1, { message: '密码不能为空' })
})

const resolver = ref(zodResolver(schema))

interface FormSubmitEvent {
    valid: boolean
    values: Record<string, any>
    states: Record<string, any>
}

const handleLogin = async ({ valid, values }: FormSubmitEvent) => {
    if (valid) {
        try {
            await authStore.login(values as LoginRequest)
            toast.add({ severity: 'success', summary: '登录成功', group: 'br', life: 3000 })
            router.push('/')
        } catch (error) {
            let errMsg = '登录失败，请重试'

            if (error instanceof Error) {
                errMsg = error.message
            } else if (typeof error === 'string') {
                errMsg = error
            }
            toast.add({ severity: 'error', summary: '登录失败', detail: errMsg, group: 'br', life: 3000 })
        } finally {
        }
    }
}
const switchToRegister = () => {
    router.push({ query: { tab: 'register' } })
}
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
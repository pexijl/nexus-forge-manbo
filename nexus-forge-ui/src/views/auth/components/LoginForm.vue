<template>
    <div class="login-form-container">
        <h2 class="font-bold text-2xl text-center">登录</h2>
        <div class="form-field">
            <FloatLabel>
                <InputText id="account" v-model="loginForm.account" fluid />
                <label for="account">用户名或邮箱</label>
            </FloatLabel>
        </div>
        <div class="form-field">
            <FloatLabel>
                <InputText id="password" v-model="loginForm.password" type="password" fluid />
                <label for="password">密码</label>
            </FloatLabel>
        </div>
        <Button label="登录" @click="handleLogin" />
        <p class="auth-switch">
            还没有账号？
            <a @click="switchToRegister">去注册</a>
        </p>
    </div>
</template>

<script setup lang="ts">
import router from '@/router'
import type { LoginRequest } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { ref } from 'vue'
import { useToast } from 'primevue/usetoast'

const toast = useToast()
const authStore = useAuthStore()

const loginForm = ref<LoginRequest>({
    account: '',
    password: ''
})


const handleLogin = async () => {
    try {
        await authStore.login(loginForm.value)
        toast.add({ severity: 'success', summary: '登录成功', group: 'br', life: 3000 })
        router.push('/')
    } catch (error) {
        toast.add({ severity: 'error', summary: '登录失败', group: 'br', life: 3000 })
    } finally {
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
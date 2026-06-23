<template>
    <div class="register-form-container">
        <h2 class="font-bold text-2xl text-center">注册</h2>

        <div class="form-field">
            <FloatLabel>
                <InputText id="username" v-model="username" fluid />
                <label for="username">用户名</label>
            </FloatLabel>
        </div>

        <div class="form-field">
            <FloatLabel>
                <InputText id="email" v-model="email" type="email" fluid />
                <label for="email">邮箱</label>
            </FloatLabel>
        </div>

        <div class="form-field">
            <FloatLabel>
                <InputText id="password" v-model="password" type="password" fluid />
                <label for="password">密码</label>
            </FloatLabel>
        </div>

        <div class="form-field">
            <FloatLabel>
                <InputText id="confirmPassword" v-model="confirmPassword" type="password" fluid />
                <label for="confirmPassword">确认密码</label>
            </FloatLabel>
        </div>

        <Button label="注册" @click="handleRegister" />

        <p class="auth-switch">
            已有账号？
            <a @click="switchToLogin">去登录</a>
        </p>
    </div>
</template>

<script setup lang="ts">
import router from '@/router'
import { ref } from 'vue'

const username = ref('')
const email = ref('')
const password = ref('')
const confirmPassword = ref('')

const handleRegister = () => {
    if (password.value !== confirmPassword.value) {
        console.error('两次输入的密码不一致')
        return
    }
    console.log(username.value, email.value, password.value)
}

const switchToLogin = () => {
    router.push({ query: { tab: 'login' } })
}
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
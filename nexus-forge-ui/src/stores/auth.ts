import { apiLogin, apiRegister } from '@/api/auth'
import type { LoginRequest, RegisterRequest } from '@/types/api'
import type { User, UserVo } from '@/types/models/user'
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useAuthStore = defineStore('auth', () => {
    const token = ref<string | null>(null)
    const userInfo = ref<UserVo | null>(null)

    async function register(req: RegisterRequest) {
        await apiRegister(req).then((res) => {
            userInfo.value = res
        })
    }

    async function login(req: LoginRequest) {
        await apiLogin(req).then((res) => {
            token.value = res.token
        })
    }

    async function fetchUserInfo() {
        // TODO: 调用获取用户信息的接口
    }

    return {
        token,
        userInfo,
        register,
        login
    }
})
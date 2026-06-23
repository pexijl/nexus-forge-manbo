import { apiLogin, apiRegister } from '@/api/auth'
import { apiGetCurrentUser } from '@/api/user'
import type { LoginRequest, RegisterRequest } from '@/types/api'
import type { User, UserInfo } from '@/types/models/user'
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useAuthStore = defineStore('auth', () => {
    const token = ref<string | null>(null)
    const userInfo = ref<UserInfo | null>(null)

    async function register(req: RegisterRequest) {
        await apiRegister(req)
    }

    async function login(req: LoginRequest) {
        await apiLogin(req).then((res) => {
            token.value = res.token
        })
    }

    async function fetchUserInfo() {
        await apiGetCurrentUser().then((res) => {
            userInfo.value = res
        })
    }

    return {
        token,
        userInfo,
        register,
        login,
        fetchUserInfo
    }
})
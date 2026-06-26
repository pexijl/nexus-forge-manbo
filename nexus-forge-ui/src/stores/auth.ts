import { apiLogin, apiRegister } from '@/api/auth'
import { apiGetUserInfo, apiUpdateUserInfo } from '@/api/user'
import type { LoginRequest, RegisterRequest } from '@/types/api'
import type { UpdateUserInfo, User, UserInfo } from '@/types/models/user'
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import CryptoJS from 'crypto-js'

const SECRET_KEY = import.meta.env.VITE_SECRET_KEY as string

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
        await apiGetUserInfo().then((res) => {
            userInfo.value = res
        })
    }

    async function updateUserInfo(data: UpdateUserInfo) {
        await apiUpdateUserInfo(data).then((res) => {
            userInfo.value = res
        })
    }

    return {
        token,
        userInfo,
        register,
        login,
        fetchUserInfo,
        updateUserInfo
    }
}, {
    persist: {
        storage: localStorage, // 使用 localStorage 进行持久化
        pick: ['token', 'userInfo'], // 只持久化部分字段
        serializer: {
            serialize: (state) => CryptoJS.AES.encrypt(JSON.stringify(state), SECRET_KEY).toString(),
            deserialize: (value) => {
                const bytes = CryptoJS.AES.decrypt(value, SECRET_KEY)
                return JSON.parse(bytes.toString(CryptoJS.enc.Utf8))
            },
        },
    }
})
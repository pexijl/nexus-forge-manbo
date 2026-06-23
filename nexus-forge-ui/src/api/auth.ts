import type { LoginRequest, RegisterRequest } from '@/types/api'
import type { UserInfo } from '@/types/models/user'
import request from '@/utils/http'

export const apiRegister = (data: RegisterRequest) => {
    return request.post<UserInfo>('/auth/register', data)
}

export const apiLogin = (data: LoginRequest) => {
    return request.post<{ token: string; }>('/auth/login', data)
}
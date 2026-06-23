import type { LoginRequest, RegisterRequest } from '@/types/api'
import type { UserVo } from '@/types/models/user'
import request from '@/utils/http'

export const apiRegister = (data: RegisterRequest) => {
    return request.post<UserVo>('/auth/register', data)
}

export const apiLogin = (data: LoginRequest) => {
    return request.post<{ token: string; }>('/auth/login', data)
}
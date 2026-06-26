import type { UpdateUserInfo, UserInfo } from '@/types/models/user'
import http from '@/utils/http'

export function apiGetUserInfo() {
    return http.get<UserInfo>('/users/me')
}

export function apiUpdateUserInfo(data: UpdateUserInfo) {
    return http.patch<UserInfo>('/users/me', { data })
}
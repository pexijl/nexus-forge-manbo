import type { UserInfo } from '@/types/models/user'
import http from '@/utils/http'

export function apiGetCurrentUser() {
    return http.get<UserInfo>('/users/me')
}
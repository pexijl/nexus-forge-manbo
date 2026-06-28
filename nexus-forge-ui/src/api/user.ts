import type { UpdateUserInfo, UserInfo } from '@/types/models/user';
import http from '@/utils/http';

export function apiGetUserInfo() {
  return http.get<UserInfo>('/users/me');
}

export function apiUpdateUserInfo(data: UpdateUserInfo) {
  return http.patch<UserInfo>('/users/me', { data });
}

export function apiUploadAvatar(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return http.post<string>('/users/me/avatar', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}
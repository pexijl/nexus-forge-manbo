import type { UpdateUserInfo, UserInfo } from '@/types/models/user';
import http from '@/utils/http';

const MAX_AVATAR_SIZE = 2 * 1024 * 1024; // 2MB

export function apiGetUserInfo() {
  return http.get<UserInfo>('/users/me');
}

export function apiUpdateUserInfo(data: UpdateUserInfo) {
  return http.patch<UserInfo>('/users/me', { data });
}

export function apiUploadAvatar(file: File) {
  // 前端预校验：避免超限文件触发 Tomcat RST 连接
  if (file.size > MAX_AVATAR_SIZE) {
    return Promise.reject(
      new Error(`图片大小不能超过 ${MAX_AVATAR_SIZE / 1024 / 1024}MB`)
    );
  }
  const formData = new FormData();
  formData.append('file', file);
  return http.post<UserInfo>('/users/me/avatar', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}
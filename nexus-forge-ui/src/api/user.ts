import type { UpdatePassword, UpdateUserInfo, UserInfo } from '@/types/models/user';
import http from '@/utils/http';
/**
 * 用户头像最大尺寸，单位字节
 */
const MAX_AVATAR_SIZE = 2 * 1024 * 1024;
/**
 * 获取当前用户信息
 */
export function apiGetUserInfo() {
  return http.get<UserInfo>('/users/me');
}
/**
 * 更新当前用户信息
 */
export function apiUpdateUserInfo(data: UpdateUserInfo) {
  return http.patch<UserInfo>('/users/me', data);
}
/**
 * 上传当前用户头像
 */
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
/**
 * 删除当前用户头像
 */
export function apiRemoveAvatar() {
  return http.delete<UserInfo>('/users/me/avatar');
}
/**
 * 更新当前用户密码
 */
export function apiUpdatePassword(data: UpdatePassword) {
  return http.post<void>('/users/me/password', data);
}

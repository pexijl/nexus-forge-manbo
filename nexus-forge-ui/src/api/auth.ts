import type { LoginRequest, RegisterRequest } from '@/types/api';
import request from '@/utils/http';

/**
 * 用户注册接口
 * @param data - 注册请求参数（用户名、密码、邮箱等）
 * @returns Promise<void>
 */
export const apiRegister = (data: RegisterRequest) => {
  return request.post('/auth/register', data);
};

/**
 * 用户登录接口
 * @param data - 登录请求参数（用户名/邮箱、密码）
 * @returns Promise<{ token: string }> 返回包含 JWT token 的响应
 */
export const apiLogin = (data: LoginRequest) => {
  return request.post<{ token: string }>('/auth/login', data);
};

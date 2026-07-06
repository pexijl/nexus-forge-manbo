import type { LoginRequest, LogoutRequest, RefreshRequest, RegisterRequest, TokenBundle } from '@/types/auth';
import request from '@/utils/http';

/**
 * 用户注册
 * @param data - 注册请求参数（用户名、密码、邮箱等）
 */
export const apiRegister = (data: RegisterRequest) =>
  request.post<void>('/auth/register', data);


/**
 * 用户登录 —— 返回 access + refresh
 * @param data - 登录请求参数（用户名/邮箱、密码）
 */
export const apiLogin = (data: LoginRequest) =>
  request.post<TokenBundle>('/auth/login', data);

/**
 * 刷新 access —— 用 refresh token 换新的 access + refresh
 * @param data - 刷新令牌请求参数
 */
export const apiRefresh = (data: RefreshRequest) =>
  request.post<TokenBundle>('/auth/refresh', data);

/**
 * 登出 —— access 从 header 取，refresh 由前端补 
 * @param data - 登出请求参数
 */
export const apiLogout = (data: LogoutRequest = {}) =>
  request.post<void>('/auth/logout', data);
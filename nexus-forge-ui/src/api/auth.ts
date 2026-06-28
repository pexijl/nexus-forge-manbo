import type { LoginRequest, RegisterRequest } from '@/types/api';
import request from '@/utils/http';

export const apiRegister = (data: RegisterRequest) => {
  return request.post('/auth/register', data);
};

export const apiLogin = (data: LoginRequest) => {
  return request.post<{ token: string }>('/auth/login', data);
};

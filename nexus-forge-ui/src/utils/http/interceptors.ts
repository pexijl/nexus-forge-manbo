import { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { AuthError, BusinessError, NetworkError } from './errors';

export function setupInterceptors(instance: AxiosInstance) {
  // 请求拦截
  instance.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => {
      // 1. 注入 Token
      const token = localStorage.getItem('token');
      if (token && config.headers) {
        config.headers.Authorization = `Bearer ${token}`;
      }

      // 2. GET 请求注入时间戳（防 IE 缓存）
      if (config.method === 'get') {
        config.params = { ...config.params, _t: Date.now() };
      }

      return config;
    },
    (error) => Promise.reject(error)
  );

  // 响应拦截
  instance.interceptors.response.use(
    (response) => {
      const { code, message, data } = response.data;

      // 业务成功（约定 code 0 或 200 为成功）
      if (code === 0 || code === 200) {
        return response;
      }

      // 业务错误：401 Token 过期
      if (code === 401) {
        return Promise.reject(new AuthError(message));
      }

      // 其他业务错误
      return Promise.reject(new BusinessError(code, message));
    },
    (error) => {
      // HTTP 层错误
      if (error.response) {
        const { status } = error.response;
        if (status === 401) return Promise.reject(new AuthError('登录已过期'));
        if (status >= 500) return Promise.reject(new NetworkError(`服务器错误 ${status}`));
      }

      // 网络中断 / timeout
      if (error.code === 'ECONNABORTED') {
        return Promise.reject(new NetworkError('请求超时'));
      }

      return Promise.reject(new NetworkError(error.message || '网络异常'));
    }
  );
}

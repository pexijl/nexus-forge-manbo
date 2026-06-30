import { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { AuthError, BusinessError, NetworkError } from './errors';
import { useAuthStore } from '@/stores/auth';

/**
 * 从 axios error 中提取后端 result.message
 * 兼容多种来源：业务 200/0 + 非业务码、HTTP 4xx/5xx 响应体
 */
function extractMessage(error: any, fallback: string): string {
  // 1. 后端 Result 响应体（业务码错误）
  if (error?.response?.data?.message) {
    return error.response.data.message;
  }
  // 2. axios 自带 message
  if (error?.message) {
    return error.message;
  }
  return fallback;
}

function notifyAuthExpired(message: string) {
  globalThis.dispatchEvent(new CustomEvent('auth:expired', { detail: message }));
}

export function setupInterceptors(instance: AxiosInstance) {
  // 请求拦截
  instance.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => {
      const authStore = useAuthStore();
      // 1. 注入 Token
      const token = authStore.token;
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

      // 业务错误
      return Promise.reject(new BusinessError(code, message || '操作失败'));
    },
    (error) => {
      const status = error.response?.status;

      // HTTP 401：未登录 / Token 过期 → 触发登录跳转
      if (status === 401) {
        const authStore = useAuthStore();
        authStore.clearAuth();
        notifyAuthExpired(extractMessage(error, '登录已过期，请重新登录'));
        return Promise.reject(
          new AuthError(extractMessage(error, '登录已过期，请重新登录'))
        );
      }

      // HTTP 403：已登录但权限不足 → 弹 toast，不跳转
      if (status === 403) {
        return Promise.reject(
          new BusinessError(
            error.response?.data?.code ?? 403,
            extractMessage(error, '无访问权限')
          )
        );
      }

      // HTTP 413：文件超限（直接透传后端 message）
      if (status === 413) {
        return Promise.reject(
          new BusinessError(
            error.response?.data?.code ?? 413,
            extractMessage(error, '文件大小超过限制')
          )
        );
      }

      // HTTP 4xx 业务错误（400/403/404 等）
      if (status && status >= 400 && status < 500) {
        return Promise.reject(
          new BusinessError(
            error.response?.data?.code ?? status,
            extractMessage(error, '请求失败')
          )
        );
      }

      // HTTP 5xx
      if (status && status >= 500) {
        return Promise.reject(
          new NetworkError(extractMessage(error, `服务器错误 ${status}`))
        );
      }

      // 网络中断 / timeout
      if (error.code === 'ECONNABORTED') {
        return Promise.reject(new NetworkError('请求超时，请重试'));
      }

      // 其他网络错误
      return Promise.reject(new NetworkError(extractMessage(error, '网络异常')));
    }
  );
}


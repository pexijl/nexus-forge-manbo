import { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { AuthError, BusinessError, NetworkError } from './errors';
import { useAuthStore } from '@/stores/auth';

// 不需要 token 的端点（白名单前缀）
const PUBLIC_PREFIXES = ['/auth/login', '/auth/register', '/auth/refresh'];
const PUBLIC_SET = new Set(PUBLIC_PREFIXES);

function isPublicEndpoint(url?: string): boolean {
  if (!url) return false;
  const path = url.split('?')[0];
  return PUBLIC_SET.has(path);   // 使用精确路径的 Set
}

/**
 * 从 axios error 中提取后端 result.message
 * 兼容多种来源：业务 200/0 + 非业务码、HTTP 4xx/5xx 响应体
 */
function extractMessage(error: unknown, fallback: string): string {
  if (error && typeof error === 'object') {
    const e = error as { response?: { data?: { message?: unknown } }; message?: unknown };
    const fromBody = e.response?.data?.message;
    if (typeof fromBody === 'string') return fromBody;
    if (typeof e.message === 'string') return e.message;
  }
  return fallback;
}

function notifyAuthExpired(message: string) {
  globalThis.dispatchEvent(new CustomEvent('auth:expired', { detail: message }));
}

export function setupInterceptors(instance: AxiosInstance) {
  // -------- 请求拦截 --------
  instance.interceptors.request.use(
    async (config: InternalAxiosRequestConfig) => {
      const authStore = useAuthStore();
      // 公开接口不打 token
      if (isPublicEndpoint(config.url)) {
        return config;
      }

      // 拿到一个新鲜 access（快过期就主动 refresh）
      const fresh = await authStore.ensureFreshAccess();
      if (fresh && config.headers) {
        config.headers.Authorization = `Bearer ${fresh}`;
      }

      //  GET 请求注入时间戳（防 IE 缓存）
      if (config.method === 'get') {
        config.params = { ...config.params, _t: Date.now() };
      }

      return config;
    },
    (error) => Promise.reject(error)
  );

  // -------- 响应拦截 --------
  instance.interceptors.response.use(
    (response) => {
      const { code, message } = response.data;
      // 业务成功（约定 code 0 或 200 为成功）
      if (code === 0 || code === 200) return response;
      // 业务错误
      return Promise.reject(new BusinessError(code, message || '操作失败'));
    },
    async (error) => {
      // 获取原始请求配置，并标记是否已重试（防止死循环）
      const original = error.config as InternalAxiosRequestConfig & {
        _retry?: boolean;
      };
      // 提取 HTTP 状态码
      const status = error.response?.status;
      // 获取认证 Store 实例（用于刷新 Token 或清除登录态）
      const authStore = useAuthStore();

      // 仅对业务接口做;refresh 接口本身失败走原本的登出流程
      if (status === 401 && !original._retry && !isPublicEndpoint(original.url)) {
        original._retry = true;

        const fresh = await authStore.ensureFreshAccess();
        if (fresh) {
          // 用新 access 重发原请求
          original.headers!.Authorization = `Bearer ${fresh}`;
          return instance.request(original);
        }

        // refresh 也挂了 → 登出
        const message = extractMessage(error, '登录已过期，请重新登录');
        authStore.clearAuth();
        notifyAuthExpired(message);
        return Promise.reject(new AuthError(message));
      }

      // -------- 401 但已经是 refresh 接口本身 --------
      if (status === 401 && isPublicEndpoint(original.url)) {
        const message = extractMessage(error, '登录已过期，请重新登录');
        authStore.clearAuth();
        notifyAuthExpired(message);
        return Promise.reject(new AuthError(message));
      }
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


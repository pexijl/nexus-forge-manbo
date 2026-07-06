import { apiLogin, apiLogout, apiRefresh, apiRegister } from '@/api/auth';
import { apiGetUserInfo, apiUpdateUserInfo } from '@/api/user';
import type { LoginRequest, RegisterRequest, TokenBundle, TokenSlot } from '@/types/auth';
import type { UpdateUserInfo, UserInfo } from '@/types/models/user';
import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import CryptoJS from 'crypto-js';
import router from '@/router';
import { AuthError } from '@/utils/http/errors';

/**
 * AES 加解密密钥，用于 Pinia persist 插件对 localStorage 中的 token 和 userInfo 进行加密存储。
 * 该密钥从环境变量 VITE_SECRET_KEY 中读取，需在 .env 文件中配置。
 */
const SECRET_KEY = import.meta.env.VITE_SECRET_KEY as string;

// access 提前 60s 视为"快过期"，避免请求飞行中过期的边界 race
const REFRESH_AHEAD_MS = 60_000;

export const useAuthStore = defineStore(
  'auth',
  () => {
    const access = ref<TokenSlot | null>(null);
    const refresh = ref<TokenSlot | null>(null);
    const userInfo = ref<UserInfo | null>(null);

    const isLoggedIn = computed(
      () => access.value !== null && refresh.value !== null,
    );

    // 写动作
    function clearAuth() {
      // bump generation 后,任何仍在飞行中的 refresh .then/.catch 都会被 generation 检查挡掉,
      // 避免登出后被静默重新登录（H1）。
      refreshGeneration++;
      access.value = null;
      refresh.value = null;
      userInfo.value = null;
    }

    /** 区分鉴权失败（必须清会话）与网络失败（让下次重试，H2）。
     *  http 拦截器抛 AuthError → 鉴权失败。
     *  axios 直接出现 401/403 → 鉴权失败。
     *  其余（断网/timeout/5xx）→ 视为瞬态,保留会话。 */
    function isAuthClassFailure(err: unknown): boolean {
      if (err instanceof AuthError) return true;
      const status = (err as { response?: { status?: number } } | null)?.response
        ?.status;
      return status === 401 || status === 403;
    }

    // ---------- 注册 / 登录 ----------
    async function register(req: RegisterRequest) {
      await apiRegister(req);
    }

    async function login(req: LoginRequest) {
      const bundle = await apiLogin(req);
      access.value = bundle.access;
      refresh.value = bundle.refresh;
      await fetchUserInfo();
    }

    async function fetchUserInfo() {
      const info = await apiGetUserInfo();
      userInfo.value = info;
    }

    async function updateUserInfo(data: UpdateUserInfo) {
      const info = await apiUpdateUserInfo(data);
      userInfo.value = info;
    }

    // ---------- 登出 ----------
    async function logout() {
      try {
        await apiLogout(refresh.value ? { refreshToken: refresh.value.token } : {});
      } catch {
        // 服务端失败也要清本地态,否则下次进入仍然"看似登录"
      } finally {
        clearAuth();
        await router.push({ name: 'auth-view', query: { tab: 'login' } });
      }
    }

    // ---------- 刷新（单飞 + generation 守卫） ----------
    let refreshing: Promise<TokenBundle | null> | null = null;
    let refreshGeneration = 0;

    async function doRefresh(): Promise<TokenBundle | null> {
      if (refreshing) return refreshing;
      if (!refresh.value) return null;

      const gen = ++refreshGeneration;
      const refreshToken = refresh.value.token;

      refreshing = apiRefresh({ refreshToken })
        .then((bundle) => {
          if (gen !== refreshGeneration) return null; // 已被登出/再次刷新取代
          access.value = bundle.access;
          refresh.value = bundle.refresh;
          return bundle;
        })
        .catch((err: unknown) => {
          if (gen !== refreshGeneration) return null;
          // 仅在"真鉴权失败"时清会话;网络抖下次再试
          if (isAuthClassFailure(err)) {
            clearAuth();
          }
          return null;
        })
        .finally(() => {
          if (gen === refreshGeneration) refreshing = null;
        });

      return refreshing;
    }

    /**
     * 获取一个可用的 access。
     * 1. 没有 access → 返回 null
     * 2. 还在有效期内 → 直接返回
     * 3. 快过期 → 主动 refresh,失败返回 null（调用方走登出流程）
     */
    async function ensureFreshAccess(): Promise<string | null> {
      if (!access.value) return null;
      const expiringSoon = access.value.expiresAt - Date.now() < REFRESH_AHEAD_MS;
      if (!expiringSoon) return access.value.token;

      const bundle = await doRefresh();
      return bundle?.access.token ?? null;
    }

    return {
      // 状态（对外暴露 ref,模板用 store.userInfo 会自动 .value）
      access,
      refresh,
      userInfo,

      // 派生
      isLoggedIn,

      // 动作
      register,
      login,
      logout,
      fetchUserInfo,
      updateUserInfo,
      clearAuth,
      ensureFreshAccess, // 给 http 拦截器用
    };
  },
  {
    persist: {
      storage: localStorage,
      pick: ['access', 'refresh', 'userInfo'],
      serializer: {
        serialize: (state) =>
          CryptoJS.AES.encrypt(JSON.stringify(state), SECRET_KEY).toString(),
        deserialize: (value) => {
          try {
            const bytes = CryptoJS.AES.decrypt(value, SECRET_KEY);
            const json = bytes.toString(CryptoJS.enc.Utf8);
            return json ? JSON.parse(json) : null;
          } catch {
            // 密钥变更或损坏 → 视为未登录
            return null;
          }
        },
      },
    },
  }
);
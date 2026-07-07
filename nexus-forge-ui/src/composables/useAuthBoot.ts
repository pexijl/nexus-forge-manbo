import { useAuthStore } from '@/stores/auth';

const REFRESH_AHEAD_MS = 60_000;

export async function bootstrapAuth(): Promise<void> {
  const auth = useAuthStore();
  if (!auth.isLoggedIn) return;

  // 启动时 access 已过期或快过期，静默刷新一次；失败就清
  const expiringSoon =
    !!auth.access && auth.access.expiresAt - Date.now() < REFRESH_AHEAD_MS;
  if (expiringSoon) {
    await auth.ensureFreshAccess();
  }
}
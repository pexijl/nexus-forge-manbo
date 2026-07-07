import { createRouter, createWebHistory } from 'vue-router';
import { routes } from './routes';
import { useAuthStore } from '@/stores/auth';

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    if (savedPosition) return savedPosition;  // 后退/前进恢复
    return { top: 0 };                         // 新跳转滚顶
  },
});

// 不需要登录就能访问的路由
const PUBLIC_NAMES = new Set(['auth-view', 'home-view']);

// 路由守卫：全局前置守卫
router.beforeEach(async (to) => {
  const auth = useAuthStore();

  if (PUBLIC_NAMES.has(String(to.name))) {
    return true;
  }

  if (!auth.isLoggedIn) {
    return { name: 'auth-view', query: { tab: 'login', redirect: to.fullPath } };
  }

  await auth.ensureFreshAccess();
  return auth.isLoggedIn
    ? true
    : { name: 'auth-view', query: { tab: 'login', redirect: to.fullPath } };
});

export default router;

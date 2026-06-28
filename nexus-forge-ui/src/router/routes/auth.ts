import type { RouteRecordRaw } from 'vue-router';

export const authRoutes: RouteRecordRaw[] = [
    {
        path: '/auth',
        name: 'auth-view',
        component: () => import('@/views/auth/AuthView.vue'),
        alias: ['/login', '/register'],   // ← 同一个组件，多个 URL
        beforeEnter: (to) => {
            if (to.query.tab) return;
            const tab = to.path === '/register' ? 'register' : 'login';
            return { ...to, query: { ...to.query, tab } };
        },
    },
];
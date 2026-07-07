import type { RouteRecordRaw } from 'vue-router';

export const homeRoutes: RouteRecordRaw[] = [
    {
        path: 'home',
        name: 'home-view',
        component: () => import('@/views/home/HomeView.vue'),
    },
];  

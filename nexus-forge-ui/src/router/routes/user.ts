import type { RouteRecordRaw } from 'vue-router';

export const userRoutes: RouteRecordRaw[] = [
    {
        path: 'profile',
        name: 'profile-view',
        component: () => import('@/views/user/ProfileView.vue'),
    },
];  

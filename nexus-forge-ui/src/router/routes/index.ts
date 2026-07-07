import type { RouteRecordRaw } from 'vue-router';
import AppLayout from '@/layout/AppLayout.vue';
import { authRoutes } from './auth';
import { homeRoutes } from './home';
import { userRoutes } from './user';

export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: AppLayout,
    children: [
      { path: '', redirect: 'home' },
      ...homeRoutes,
      ...userRoutes,
    ],
  },
  ...authRoutes,
];
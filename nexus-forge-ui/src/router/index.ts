import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import AppLayout from '@/layout/AppLayout.vue'

const routes: RouteRecordRaw[] = [
    {
        path: '/',
        component: AppLayout,
        children: [
            { path: '', redirect: 'home' },
            {
                path: 'home',
                name: 'home-view',
                component: () => import('@/views/home/HomeView.vue')
            }
        ]
    },
    {
        path: '/login',
        name: 'login',
        redirect: { name: 'auth', query: { tab: 'login' } }
    },
    {
        path: '/register',
        name: 'register',
        redirect: { name: 'auth', query: { tab: 'register' } }
    },
    {
        path: '/auth',
        name: 'auth',
        component: () => import('@/views/auth/AuthView.vue'),
        beforeEnter: (to) => {
            // 如果没有指定 tab 参数，默认重定向到登录页
            if (!to.query.tab) {
                return { name: 'auth', query: { tab: 'login' } }
            }
        }
    }
]

const router = createRouter({
    history: createWebHistory(import.meta.env.BASE_URL),
    routes
})

export default router
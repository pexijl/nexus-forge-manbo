import type { RouteRecordRaw } from 'vue-router';
import BasicPanel from '@/views/user/panels/BasicPanel.vue';
import ContactPanel from '@/views/user/panels/ContactPanel.vue';
import NotificationPanel from '@/views/user/panels/NotificationPanel.vue';
import SecurityPanel from '@/views/user/panels/SecurityPanel.vue';

export const userRoutes: RouteRecordRaw[] = [
    {
        path: 'profile',
        name: 'profile-view',
        redirect: { name: 'profile-basic' },
        component: () => import('@/views/user/ProfileView.vue'),
        children: [
            { path: '', name: 'profile-basic', component: BasicPanel },
            { path: 'contact', name: 'profile-contact', component: ContactPanel },
            { path: 'notifications', name: 'profile-notifications', component: NotificationPanel },
            { path: 'security', name: 'profile-security', component: SecurityPanel },
        ]
    },
];  

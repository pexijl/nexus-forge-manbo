type BadgeVariant = 'info' | 'success' | 'warn' | 'muted';

export const accountTabs: Array<{
    id: string;
    routeName?: string;
    label: string;
    icon: string;
    badge?: string;
    badgeVariant?: BadgeVariant;
    badgeDot?: boolean;
}> = [
    {
        id: 'profile',
        routeName: 'profile-basic',
        label: '基础资料',
        icon: 'pi pi-user',
    },
    {
        id: 'contact',
        routeName: 'profile-contact',
        label: '联系方式',
        icon: 'pi pi-envelope',
    },
    {
        id: 'notifications',
        routeName: 'profile-notifications',
        label: '通知与隐私',
        icon: 'pi pi-bell',
        badge: '3',
        badgeVariant: 'muted',
    },
    {
        id: 'security',
        routeName: 'profile-security',
        label: '账号安全',
        icon: 'pi pi-shield',
        badge: '已开启',
        badgeVariant: 'success',
        badgeDot: true,
    },
] as const;

export type { BadgeVariant };

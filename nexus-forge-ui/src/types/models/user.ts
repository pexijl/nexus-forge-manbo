/**
 * 用户状态枚举
 */
export const UserStatus = {
  ACTIVE: 1,
  INACTIVE: 0,
  BANNED: -1,
  DELETED: -2,
} as const;

export type UserStatus = (typeof UserStatus)[keyof typeof UserStatus];

/**
 * 用户信息接口
 */
export interface User {
  id: number;
  username: string;
  email: string;
  nickname: string;
  avatarUrl: string;
  phone: string;
  status: UserStatus;
  roles: string[];
  lastLoginAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface UserInfo {
  id: number;
  username: string;
  email: string;
  nickname: string;
  avatarUrl: string;
  phone: string;
  status: UserStatus;
  roles: string[];
  lastLoginAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateUserInfo {
  email?: string;
  nickname?: string;
  phone?: string;
}

export interface UpdatePassword {
  oldPassword: string;
  newPassword: string;
}

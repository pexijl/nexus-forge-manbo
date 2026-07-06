/** 注册请求参数接口 */
export interface RegisterRequest {
    /**
     * 用户名
     */
    username: string;
    /**
     * 邮箱
     */
    email: string;
    /**
     * 密码
     */
    password: string;
}

/** 登录请求参数接口 */
export interface LoginRequest {
    /**
     * 用户名或邮箱
     */
    account: string;
    /**
     * 密码
     */
    password: string;
}

/** 刷新令牌请求参数接口 */
export interface RefreshRequest {
    refreshToken: string;
}

/** 登出请求参数接口 */
export interface LogoutRequest {
    refreshToken?: string;
}

/** 单个 token 槽位（token + jti + 过期时间）  */
export interface TokenSlot {
    /** 访问令牌 */
    token: string;
    /** JWT ID */
    jti: string;
    /** epoch millis（毫秒时间戳） */
    expiresAt: number;
}


/** 后端 /auth/login 与 /auth/refresh 的 data 形状 */
export interface TokenBundle {
    /** 访问令牌 */
    access: TokenSlot;
    /** 刷新令牌 */
    refresh: TokenSlot;
}
export interface ApiResponse<T> {
    code: number;
    message: string;
    data: T;
}

/**
 * 请求配置接口
 */
export interface RequestConfig {
    /**
     * 是否显示错误提示，默认为 true
     */
    showError?: boolean;
    /**
     * 是否显示加载提示，默认为 true
     */
    showLoading?: boolean;
    /**
     * 请求失败时的重试次数，默认为 0（不重试）
     */
    retry?: number;
}

// 认证相关类型, TODO: 未来可以考虑将这些类型拆分到单独的文件中

/**
 * 登录请求参数接口
 */
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

/**
 * 注册请求参数接口
 */
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
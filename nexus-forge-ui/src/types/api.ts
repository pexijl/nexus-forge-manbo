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
import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import type { ApiResponse, RequestConfig } from '@/types/api'
import { setupInterceptors } from './interceptors'

class HttpClient {
  private readonly instance: AxiosInstance

  constructor(baseURL: string, timeout = 10000) {
    this.instance = axios.create({ baseURL, timeout })
    setupInterceptors(this.instance)
  }

  async request<T = unknown>(config: AxiosRequestConfig & RequestConfig): Promise<T> {
    const res = await this.instance.request<ApiResponse<T>>(config)
      return res.data.data
  }

  get<T = unknown>(url: string, config?: AxiosRequestConfig & RequestConfig): Promise<T> {
    return this.request<T>({ ...config, url, method: 'GET' })
  }

  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig & RequestConfig): Promise<T> {
    return this.request<T>({ ...config, url, method: 'POST', data })
  }

  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig & RequestConfig): Promise<T> {
    return this.request<T>({ ...config, url, method: 'PUT', data })
  }

  delete<T = unknown>(url: string, config?: AxiosRequestConfig & RequestConfig): Promise<T> {
    return this.request<T>({ ...config, url, method: 'DELETE' })
  }
}

export const http = new HttpClient(import.meta.env.VITE_API_BASE_URL || '/api')
export default http
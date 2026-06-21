import { type AxiosRequestConfig } from 'axios'

const pendingMap = new Map<string, AbortController>()

export function addPending(config: AxiosRequestConfig): AxiosRequestConfig {
  const controller = new AbortController()
  config.signal = controller.signal
  pendingMap.set(getKey(config), controller)
  return config
}

export function removePending(config: AxiosRequestConfig) {
  pendingMap.delete(getKey(config))
}

export function cancelAll() {
  pendingMap.forEach(controller => controller.abort())
  pendingMap.clear()
}

function getKey(config: AxiosRequestConfig): string {
  return [config.method, config.url, JSON.stringify(config.params || {})].join('&')
}
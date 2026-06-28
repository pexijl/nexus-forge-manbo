import http from '@/utils/http';

export function hello() {
  return http.get<string>('/hello');
}

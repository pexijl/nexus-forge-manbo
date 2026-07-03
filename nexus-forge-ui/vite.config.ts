import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import Components from 'unplugin-vue-components/vite';
import { PrimeVueResolver } from '@primevue/auto-import-resolver';
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig(({ }) => {
  return {
    plugins: [
      vue(),
      tailwindcss(),
      Components({
        resolvers: [
          PrimeVueResolver()
        ]
      })
    ],
    base: '/',
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true,
          // rewrite: (path) => path.replace(/^\/api/, ''), // 如果后端不需要 /api 前缀
        },
      },
    },
  }
})

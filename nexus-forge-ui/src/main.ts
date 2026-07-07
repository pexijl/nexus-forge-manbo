import { createApp } from 'vue';
import PrimeVue from 'primevue/config';
import './style.css';
import App from './App.vue';
import router from './router';
import './styles/main.scss';
import { MyPreset } from './themes/index.ts';
import { createPinia } from 'pinia';
import ToastService from 'primevue/toastservice';
import ConfirmationService from 'primevue/confirmationservice';
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate';
import 'primeicons/primeicons.css';
import { bootstrapAuth } from '@/composables/useAuthBoot';

const app = createApp(App);
const pinia = createPinia();

pinia.use(piniaPluginPersistedstate);

// 1. 安装 Pinia（useAuthStore 需要）
app.use(pinia);

// 2. 安装路由（bootstrapAuth 中如果触发跳转需要）
app.use(router);

// 3. 安装其他插件
app.use(ToastService);
app.use(ConfirmationService);
app.use(PrimeVue, {
  license: import.meta.env.VITE_PRIMEVUE_LICENSE_KEY,
  theme: {
    preset: MyPreset,
    options: {
      darkModeSelector: '.dark',
    },
  },
});

// 4. 启动认证初始化，完成后挂载
bootstrapAuth().finally(() => {
  app.mount('#app');
});
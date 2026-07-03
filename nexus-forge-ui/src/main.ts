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

const app = createApp(App);
const pinia = createPinia();

pinia.use(piniaPluginPersistedstate);

// 路由集成
app.use(router);
app.use(pinia);
app.use(ToastService);
app.use(ConfirmationService); 

app.use(PrimeVue, {
  license: import.meta.env.VITE_PRIMEVUE_LICENSE_KEY,
  theme: {
    preset: MyPreset,
    options: {
      // 只在 <html class="dark"> 时启用暗色模式
      darkModeSelector: '.dark',
    },
  },
});

app.mount('#app');

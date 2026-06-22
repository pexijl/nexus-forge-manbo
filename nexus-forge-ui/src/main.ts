import { createApp } from 'vue'
import PrimeVue from 'primevue/config';
import Aura from '@primeuix/themes/aura';
import './style.css'
import App from './App.vue'
import router from './router'
import './styles/main.scss'

const app = createApp(App);
// 路由集成
app.use(router);

app.use(PrimeVue, {
    theme: {
        preset: Aura,
        options: {
            // 只在 <html class="dark"> 时启用暗色模式
            darkModeSelector: '.dark'
        }
    }
});

app.mount('#app')

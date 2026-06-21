import { createApp } from 'vue'
import PrimeVue from 'primevue/config';
import Aura from '@primeuix/themes/aura';
import './style.css'
import App from './App.vue'

const app = createApp(App);

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

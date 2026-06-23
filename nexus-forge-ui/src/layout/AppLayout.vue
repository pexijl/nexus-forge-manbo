<template>
    <div class="app-layout-container">
        <AppToolbar v-model:visible="visible" />
        <main class="main-content">
            <router-view />
            <AppSidePanel v-model:visible="visible">
                <template #header>
                    <span>自定义面板标题</span>
                </template>
                <p>面板内容</p>
            </AppSidePanel>
        </main>
    </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import AppToolbar from './components/AppToolbar.vue';
import AppSidePanel from './components/AppSidePanel.vue';

const visible = ref(false);
</script>

<style scoped lang="scss">
.app-layout-container {
    display: flex;
    flex-direction: column;
    min-height: 100vh;
}

.main-content {
    position: relative;   /* 作为 absolute 定位的参照 */
    overflow: hidden;     /* 遮罩不会溢出到 toolbar */
    flex: 1;
}
</style>
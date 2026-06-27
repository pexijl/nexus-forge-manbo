<template>
    <div class="app-layout-container">
        <AppToolbar @toggle="layoutStore.toggleSidebar" />
        <main class="main-content">
            <router-view />
            <AppSidePanel v-model:visible="sidebarVisible">
                <template #header>
                    <span>自定义面板标题</span>
                </template>
                <p>面板内容</p>
            </AppSidePanel>
        </main>
    </div>
</template>

<script setup lang="ts">
import AppToolbar from './components/AppToolbar.vue';
import AppSidePanel from './components/AppSidePanel.vue';
import { useLayoutStore } from '@/stores/layout.ts';
import { storeToRefs } from 'pinia';

const layoutStore = useLayoutStore()
const { sidebarVisible } = storeToRefs(layoutStore)
</script>

<style scoped lang="scss">
.app-layout-container {
    display: flex;
    flex-direction: column;
    min-height: 100vh;        /* 整页自然滚动，sticky 子元素可生效 */
}

.main-content {
    position: relative;
    flex: 1;
}
</style>
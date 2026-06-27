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
    height: 100vh;            /* 锁定视口高度，交给内部 main 自适应 */
}

.main-content {
    position: relative;       /* 作为 absolute 定位的参照 */
    flex: 1;                  /* 占满 toolbar 之外的空间 */
    min-height: 0;            /* 关键：允许子项收缩，让 overflow 生效 */
    overflow: hidden;         /* 遮罩不会溢出到 toolbar */
}
</style>
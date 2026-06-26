<template>
    <div class="app-toolbar-container">
        <!-- 左侧：Logo + 导航 -->
        <div class="toolbar-left">
            <Button icon="pi pi-align-justify" variant="text" @click="emit('toggle')" />
        </div>
        <!-- 右侧：操作区 -->
        <div class="toolbar-right">
            <Button icon="pi pi-user" variant="text" @click="router.push({ name: 'profile-view' })"/>
        </div>
    </div>
</template>

<script setup lang="ts">

import  router  from '@/router'

const emit = defineEmits<{
    (e: 'toggle'): void
}>()
</script>

<style scoped lang="scss">
.app-toolbar-container {
    height: 60px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 var(--space-lg, 24px);
    background: var(--bg-primary);
    border-bottom: 1px solid var(--border-color);
    box-shadow: var(--shadow-md);
    position: sticky;
    top: 0;
    z-index: 100;
}

.toolbar-left {
    display: flex;
    align-items: center;
    gap: var(--space-lg, 24px);
}

.toolbar-right {
    display: flex;
    align-items: center;
    gap: 12px;
}
</style>
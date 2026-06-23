<template>
    <div>
        <Transition name="fade">
            <div v-if="visible" class="side-panel-mask" @click="close" />
        </Transition>
        <!-- 面板 -->
        <Transition name="slide">
            <div v-if="visible" class="app-side-panel">
                <div class="side-panel-header">
                    <slot name="header">
                        <span>面板标题</span>
                    </slot>
                    <button class="close-btn" @click="close">&times;</button>
                </div>
                <div class="side-panel-body">
                    <slot />
                </div>
            </div>
        </Transition>
    </div>
</template>

<script setup lang="ts">
defineProps<{
    visible: boolean
}>()

const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void
}>()

function close() {
    emit('update:visible', false)
}
</script>

<style scoped lang="scss">
@use './AppSidePanel.scss';
</style>
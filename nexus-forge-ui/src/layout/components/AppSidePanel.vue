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
import { watch, onUnmounted } from 'vue'

const props = defineProps<{
    visible: boolean
}>()

const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void
}>()

function close() {
    emit('update:visible', false)
}

// 打开面板时锁 body 滚动, 关闭时恢复 —— 模态抽屉应阻止底层滚动
let savedOverflow = ''
watch(
    () => props.visible,
    (v) => {
        if (v) {
            savedOverflow = document.body.style.overflow
            document.body.style.overflow = 'hidden'
        } else {
            document.body.style.overflow = savedOverflow
        }
    }
)

// 组件销毁时兜底恢复, 避免页面卡死
onUnmounted(() => {
    document.body.style.overflow = savedOverflow
})
</script>

<style scoped lang="scss">
@use './AppSidePanel.scss';
</style>
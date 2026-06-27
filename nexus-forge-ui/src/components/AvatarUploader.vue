<template>
    <div class="avatar-uploader">
        <div class="avatar-row">
            <div class="avatar avatar-xl" aria-hidden="true">林</div>
            <div class="avatar-meta">
                <div class="avatar-name">{{ name }}</div>
                <div class="avatar-id mono">{{ userId }}</div>
                <div class="body-sm body-muted" style="margin-top: 4px">注册于 2024 年 3 月 · 启明科技</div>
            </div>
            <div class="avatar-actions">
                <button class="btn btn-secondary btn-sm" type="button">更换头像</button>
                <button class="btn btn-ghost btn-sm" type="button">移除</button>
            </div>
        </div>
    </div>
</template>

<script setup lang="ts">
defineProps<{
    name?: string
    userId?: string
}>()
</script>

<style scoped lang="scss">
.avatar-uploader {
    /* 占位组件, 后续接入裁剪/上传 */
}
</style>
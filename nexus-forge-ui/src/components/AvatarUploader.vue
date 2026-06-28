<template>
  <!-- TODO: 增加 loading 状态 -->
  <button
    class="avatar-wrapper"
    type="button"
    :aria-label="avatarUrl ? '更换头像' : '上传头像'"
    @click="$emit('upload')"
  >
    <!-- 头像显示 -->
    <div class="avatar-display">
      <Avatar v-if="avatarUrl" :image="avatarUrl" unstyled class="avatar-display__image" />
      <i v-else class="pi pi-user avatar-display__placeholder"></i>
    </div>
    <!-- 悬浮遮罩 -->
    <div class="avatar-overlay">
      <i class="pi pi-camera avatar-overlay__icon"></i>
      <span class="avatar-overlay__text">更换头像</span>
    </div>
  </button>
</template>

<script setup lang="ts">
defineProps<{ avatarUrl?: string }>();
defineEmits<{ (e: 'upload'): void }>();
</script>

<style scoped lang="scss">
.avatar-wrapper {
  /* 重置 <button> 浏览器默认外观（Safari 旧版/某些主题会显示蓝色高亮） */
  appearance: none;
  background: transparent;
  border: none;
  padding: 0;

  position: relative;
  width: 120px;
  height: 120px;
  border-radius: 50%;
  overflow: hidden;
  cursor: pointer;
  flex-shrink: 0;

  &:focus-visible {
    outline: none;
    box-shadow: var(--focus-ring);
  }
}

.avatar-display {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: var(--surface-hover);
  overflow: hidden;

  &__image {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  &__placeholder {
    font-size: var(--text-5xl);
    color: var(--muted);
  }
}

.avatar-overlay {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  position: absolute;
  
  inset: 0;
  color: #fff;
  gap: var(--space-2);
  font-size: var(--text-2xl);
  background: var(--overlay-scrim);
  opacity: 0;
  transition: opacity 0.2s;

  .avatar-wrapper:hover & {
    opacity: 1;
  }

  &__icon {
    font-size: var(--text-3xl);
  }

  &__text {
    font-size: var(--text-sm);
    font-weight: 500;
  }
}
</style>

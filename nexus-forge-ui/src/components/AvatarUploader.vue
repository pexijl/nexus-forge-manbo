<template>
  <!-- TODO: 增加 loading 状态 -->
  <button
    class="avatar-wrapper"
    type="button"
    :aria-label="avatarUrl ? '更换头像' : '上传头像'"
    @click="fileInput?.click()"
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
  <!-- 隐藏的文件输入 -->
  <input
    id="avatar-upload"
    aria-label="上传头像"
    ref="fileInput"
    type="file"
    accept="image/*"
    hidden
    @change="onFileChange"
  />
</template>

<script setup lang="ts">
import { ref } from 'vue';
// TODO: 组件自己实现上传逻辑，或者使用第三方库（如 vue-filepond）
defineProps<{ avatarUrl?: string }>();
const emit = defineEmits<{ (e: 'change', file: File): void }>();

const fileInput = ref<HTMLInputElement>();

const onFileChange = (e: Event) => {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (file) emit('change', file);
  // 清空，允许重复选择同一文件
  (e.target as HTMLInputElement).value = '';
};
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
  width: 100%;
  height: 100%;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: var(--surface-hover);
  overflow: hidden;

  &__image {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;

    :deep(.p-avatar-image) {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  &__placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
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

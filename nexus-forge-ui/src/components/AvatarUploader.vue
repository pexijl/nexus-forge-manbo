<template>
  <button
    class="avatar-wrapper"
    type="button"
    :class="{ 'is-uploading': loading }"
    :disabled="loading"
    :aria-busy="loading"
    :aria-label="avatarUrl ? '更换头像' : '上传头像'"
    @click="handleClick"
  >
    <!-- 头像显示 -->
    <div class="avatar-display">
      <Avatar v-if="avatarUrl" :image="avatarUrl" unstyled class="avatar-display__image" />
      <i v-else class="pi pi-user avatar-display__placeholder"></i>
    </div>
    <!-- 悬浮遮罩 -->
    <div class="avatar-overlay">
      <i v-if="loading" class="pi pi-spin pi-spinner avatar-overlay__icon"></i>
      <template v-else>
        <i class="pi pi-camera avatar-overlay__icon"></i>
        <span class="avatar-overlay__text">更换头像</span>
      </template>
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
    :disabled="loading"
    @change="onFileChange"
  />
</template>

<script setup lang="ts">
import { ref } from 'vue';

const props = defineProps<{
  avatarUrl?: string;
  loading?: boolean;
}>();

const emit = defineEmits<{ (e: 'change', file: File): void }>();

const fileInput = ref<HTMLInputElement>();

// 防止 loading 中重复点击触发文件选择
const handleClick = () => {
  if (props.loading) return;
  fileInput.value?.click();
};

const onFileChange = (e: Event) => {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (file) emit('change', file);
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

  /* loading 时禁用 hover 效果 */
  &.is-uploading {
    cursor: not-allowed;
    pointer-events: none; /* 兜底，防止悬浮遮罩误触 */
  }

  &:disabled {
    cursor: not-allowed;
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

/* spinner 动画（PrimeIcons 自带 pi-spin）*/
.avatar-overlay__icon {
  &.pi-spinner {
    font-size: var(--text-3xl);
    animation: pi-spin 2s linear infinite;
  }
}

@keyframes pi-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>

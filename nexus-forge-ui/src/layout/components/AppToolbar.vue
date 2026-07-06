<template>
  <div class="app-toolbar-container">
    <!-- 左侧：Logo + 导航 -->
    <div class="toolbar-left">
      <Button icon="pi pi-align-justify" variant="text" @click="emit('toggle')" />
    </div>
    <!-- 右侧：操作区 -->
    <div class="toolbar-right">
      <Avatar
        class="toolbar-avatar"
        :image="authStore.userInfo?.avatarUrl"
        :icon="authStore.userInfo?.avatarUrl ? undefined : 'pi pi-user'"
        shape="circle"
        size="large"
        @click="onAvatarClick"
      />
      <!-- 弹出框 -->
      <Popover ref="avatarPopover">
        <div class="">
          <div class="flex flex-col gap-2">
            <Button text @click="onProfileClick">个人主页</Button>
            <Button text severity="danger" @click="onLogoutClick">退出登录</Button>
          </div>
        </div>
      </Popover>
    </div>
  </div>
</template>

<script setup lang="ts">
import router from '@/router';
import { useAuthStore } from '@/stores/auth';
import type Popover from 'primevue/popover';
import { ref } from 'vue';

const authStore = useAuthStore();

const avatarPopover = ref<InstanceType<typeof Popover> | null>(null);

const onAvatarClick = (e: Event) => {
  avatarPopover.value?.toggle(e);
};

const onProfileClick = () => {
  router.push('/profile');
  avatarPopover.value?.hide();
};

const onLogoutClick = async () => {
  await authStore.logout(); // store 内部已经跳了
  avatarPopover.value?.hide();
};

const emit = defineEmits<{
  (e: 'toggle'): void;
}>();
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

.toolbar-avatar {
  cursor: pointer;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease,
    filter 0.2s ease;

  &:hover {
    transform: scale(1.15);
    box-shadow: 0 0 0 3px color-mix(in oklab, var(--accent), transparent 60%);
    filter: brightness(1.05);
  }
}
</style>

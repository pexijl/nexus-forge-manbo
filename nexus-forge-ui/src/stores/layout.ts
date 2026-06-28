import { defineStore } from 'pinia';
import { ref } from 'vue';

export const useLayoutStore = defineStore('layout', () => {
  const sidebarVisible = ref(false);

  // 断点判断, 移动端 < 768px, 平板 768px - 1024px, 桌面端 > 1024px
  const windowWidth = ref(window.innerWidth);
  const isMobile = ref(window.innerWidth < 768);
  const isTablet = ref(window.innerWidth >= 768 && window.innerWidth < 1024);
  const isDesktop = ref(window.innerWidth >= 1024);

  function toggleSidebar() {
    sidebarVisible.value = !sidebarVisible.value;
  }

  return {
    sidebarVisible,
    windowWidth,
    isMobile,
    isTablet,
    isDesktop,
    toggleSidebar,
  };
});

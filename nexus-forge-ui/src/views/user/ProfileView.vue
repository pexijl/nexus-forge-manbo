<template>
    <div class="profile-view-container">
        <!-- 头像 + 侧边栏 -->
        <aside class="profile-sidebar">
            <!-- 这里放头像、菜单等 -->
        </aside>
        <!-- 可切换内容区 -->
        <main class="profile-content">
            <!-- 这里放个人资料详情 -->
        </main>
    </div>
</template>

<script setup lang="ts">
// 你的逻辑代码
</script>

<style scoped lang="scss">
.profile-view-container {
    max-width: 1200px; // 控制整体最大宽度
    margin: 20px auto; // 水平居中 + 上下边距
    padding: 0 var(--padding-md); // 左右各留20px空白（小屏保护）
    display: flex;
    flex-direction: row; // 默认就是row，可以省略
    gap: var(--gap-md); // 子元素间距，替代 margin-right
    align-items: flex-start; // 顶部对齐（默认就是，显式写出来更清晰）
}

.profile-sidebar {
    flex: 0 0 200px; // 固定宽度，不伸缩（等同于 width: 200px; flex-shrink: 0;）
    height: 400px;
    background-color: #f0f0f0;
    border-radius: var(--radius-lg); // 加点圆角更现代
}

.profile-content {
    flex: 1; // 占满剩余宽度
    min-width: 0; // 防止内容溢出
    height: 400px;
    background-color: #ffffff;
    border: 1px solid #e0e0e0;
    border-radius: var(--radius-lg);
    padding: var(--padding-lg); // 内容区内部留白
}

// 可选：移动端适配（屏幕小于768px时，侧边栏变顶部导航）
@media (max-width: 768px) {
    .profile-view-container {
        flex-direction: column; // 改为纵向排列
        padding: 0 var(--padding-sm); // 小屏留白减少
    }

    .profile-sidebar {
        flex: none; // 取消固定宽度限制
        width: 100%; // 占满
        height: 80px; // 变成横条导航
        margin-bottom: var(--margin-md);
    }

    .profile-content {
        height: auto; // 高度自适应
        min-height: 300px;
    }
}
</style>
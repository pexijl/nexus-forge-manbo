<template>
    <div class="profile-view-container">
        <!-- 头像 + 侧边栏 -->
        <aside class="profile-sidebar">
            <!-- 头像 -->
            
 
        </aside>
        <!-- 可切换内容区 -->
        <main class="profile-content hide-scrollbar">
            <!-- 这里放个人资料详情 -->
             <div v-for="n in 500" :key="n">
                <p>个人资料内容行 {{ n }}</p>
             </div>
        </main>
    </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';

</script>

<style scoped lang="scss">
.profile-view-container {
    max-width: 1200px; // 控制整体最大宽度
    margin: 0 auto; // 水平居中（高度交给父级 main-content）
    padding: var(--padding-lg); // 左右各留20px空白（小屏保护）
    display: flex;
    flex-direction: row; // 默认就是row，可以省略
    gap: var(--gap-md); // 子元素间距，替代 margin-right

    /* 关键：用 100% 拿到 AppLayout main-content 给的高度 */
    height: 100%;
    min-height: 0;
    overflow: hidden;
}

.profile-sidebar {
    flex: 0 0 200px; // 固定宽度，不伸缩（等同于 width: 200px; flex-shrink: 0;）
    align-self: stretch; // 撑满容器高度
    background-color: #f0f0f0;
    border-radius: var(--radius-lg); // 加点圆角更现代

    display: flex;
    flex-direction: column; // 纵向排列
    align-items: center; // 水平居中
    padding: var(--padding-lg); // 内部留白

    /* 关键：sticky 让 sidebar 自己不滚动 */
    position: sticky;
    top: 0;
}

.profile-content {
    flex: 1; // 占满剩余宽度
    min-width: 0; // 防止内容溢出

    background-color: #ffffff;
    border: 1px solid #e0e0e0;
    border-radius: var(--radius-lg);
    padding: var(--padding-lg); // 内容区内部留白

    /* 关键：只让右侧内容区滚动 */
    overflow-y: auto;
    overflow-x: hidden;
}

// 可选：移动端适配（屏幕小于768px时，侧边栏变顶部导航）
@media (max-width: 768px) {
    .profile-view-container {
        flex-direction: column; // 改为纵向排列
        padding: var(--padding-sm); // 小屏留白减少
        height: auto;        // 小屏让整页自己滚
        overflow: visible;
    }

    .profile-sidebar {
        position: static;    // 取消 sticky
        flex: none;          // 取消固定宽度限制
        width: 100%;         // 占满
        height: auto;        // 变成横条导航
        margin-bottom: var(--margin-md);
    }

    .profile-content {
        overflow: visible;   // 小屏不限制滚动
        min-height: 300px;
    }
}
</style>
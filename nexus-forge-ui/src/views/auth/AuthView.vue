<template>
    <div class="auth-view-container">
        <div class="auth-content">
            <Transition name="auth-fade" mode="out-in">
                <LoginForm v-if="activeTab === 'login'" key="login" />
                <RegisterForm v-else key="register" />
            </Transition>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import LoginForm from './components/LoginForm.vue'
import RegisterForm from './components/RegisterForm.vue'

const route = useRoute()
const activeTab = computed(() => route.query.tab)

</script>

<style scoped lang="scss">
@use './AuthView.scss';
</style>
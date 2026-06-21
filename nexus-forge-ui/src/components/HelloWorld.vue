<template>
  <div>
    <h1>Hello World</h1>
    <Button @click="handleClick">Primary Button</Button>
  </div>
</template>

<script setup lang="ts">
import { hello } from '@/api/health'


const handleClick = () => {
  hello().then(res => {
    console.log(res)
  })
}

</script>

<style scoped></style>
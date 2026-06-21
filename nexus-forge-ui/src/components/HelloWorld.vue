<template>
  <div>
    <h1>Hello World</h1>
    <Button @click="handleClick">Primary Button</Button>
  </div>
</template>

<script setup lang="ts">
const handleClick = () => {
  console.log('Button clicked!');
}

</script>

<style scoped>

</style>
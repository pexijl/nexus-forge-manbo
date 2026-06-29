<script setup lang="ts"></script>

<template>
  <Toast position="top-left" group="tl" />
  <Toast position="top-right" group="tr" />
  <Toast position="bottom-right" group="br" />
  <Toast position="bottom-left" group="bl" />
  <ConfirmDialog group="positioned"></ConfirmDialog>
  <router-view />
</template>

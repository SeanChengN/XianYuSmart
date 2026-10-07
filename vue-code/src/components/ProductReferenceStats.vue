<script setup lang="ts">
defineProps<{ soldCountText?: string | null; wantCountText?: string | null }>()
</script>

<template>
  <div class="product-reference-stats" aria-label="商品参考数据">
    <span>{{ soldCountText || '已售：未提供' }}</span>
    <span>{{ wantCountText || '想要：未提供' }}</span>
  </div>
</template>

<style scoped>
.product-reference-stats { display: flex; flex-wrap: wrap; gap: 6px 14px; margin-top: 8px; color: #475467; font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
</style>

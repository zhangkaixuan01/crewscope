<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import M7AuthStatePreview from '../stories/M7AuthStatePreview.vue'
</script>

<template>
  <Story title="M7/Auth foundation · Contracts" :layout="{ type: 'single', iframe: true, width: 1280 }">
    <Variant title="Light brand contrast"><M7AuthStatePreview state="login" brand-tone="light" /></Variant>
    <Variant title="Dark brand contrast"><M7AuthStatePreview state="login" brand-tone="dark" stage-tone="neutral" /></Variant>
    <Variant title="Reduced motion"><M7AuthStatePreview state="loading" motion="reduced" /></Variant>
  </Story>
</template>

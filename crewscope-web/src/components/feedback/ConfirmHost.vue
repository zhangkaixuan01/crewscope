<script setup lang="ts">
import BaseButton from '../base/BaseButton.vue'
import { useConfirm } from '../../composables/useConfirm'
const { request, settle } = useConfirm()
</script>
<template><Teleport to="body"><div v-if="request" class="confirm-host" role="presentation"><section class="confirm-dialog" role="alertdialog" aria-modal="true" :aria-labelledby="`confirm-${request.id}`"><h2 :id="`confirm-${request.id}`">{{ request.title }}</h2><p v-if="request.description">{{ request.description }}</p><footer><BaseButton variant="ghost" @click="settle(false)">{{ request.cancelLabel }}</BaseButton><BaseButton :variant="request.danger ? 'danger' : 'primary'" @click="settle(true)">{{ request.confirmLabel }}</BaseButton></footer></section></div></Teleport></template>
<style scoped>
.confirm-host { position: fixed; z-index: var(--cs-z-modal); inset: 0; display: grid; place-items: center; padding: var(--cs-space-6); background: rgb(0 0 0 / 48%); }.confirm-dialog { width: min(460px, 100%); padding: var(--cs-space-6); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); color: var(--cs-text); box-shadow: var(--cs-shadow-float); }.confirm-dialog h2 { margin: 0 0 var(--cs-space-2); font-size: var(--cs-text-lg); }.confirm-dialog p { margin: 0; color: var(--cs-text-secondary); }.confirm-dialog footer { display: flex; justify-content: flex-end; gap: var(--cs-space-2); margin-top: var(--cs-space-6); }
</style>

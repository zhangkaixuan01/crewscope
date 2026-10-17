<script setup lang="ts">
import { computed, ref } from 'vue'
import BaseButton from '../base/BaseButton.vue'
import { useConfirm } from '../../composables/useConfirm'
import { useFocusTrap } from '../../composables/useFocusTrap'
const { request, settle } = useConfirm()
const surface = ref<HTMLElement | null>(null)
// alertdialog 是全站第二高频的浮层（仅次于 BaseDialog），此前既不困焦也不响应 Esc。
useFocusTrap(surface, computed(() => request.value != null))
</script>
<template><Teleport to="body"><div v-if="request" class="confirm-host" role="presentation"><section ref="surface" class="confirm-dialog" role="alertdialog" aria-modal="true" :aria-labelledby="`confirm-${request.id}`" tabindex="-1" @keydown.esc="settle(false)"><h2 :id="`confirm-${request.id}`">{{ request.title }}</h2><p v-if="request.description">{{ request.description }}</p><footer><BaseButton variant="ghost" @click="settle(false)">{{ request.cancelLabel }}</BaseButton><BaseButton :variant="request.danger ? 'danger' : 'primary'" @click="settle(true)">{{ request.confirmLabel }}</BaseButton></footer></section></div></Teleport></template>
<style scoped>
.confirm-host { position: fixed; z-index: var(--cs-z-dialog); inset: 0; display: grid; place-items: center; padding: var(--cs-space-24); background: var(--cs-scrim); }.confirm-dialog { width: min(460px, 100%); padding: var(--cs-space-24); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); color: var(--cs-text); box-shadow: var(--cs-shadow-float); }.confirm-dialog h2 { margin: 0 0 var(--cs-space-8); font-size: var(--cs-text-md); }.confirm-dialog p { margin: 0; color: var(--cs-text-secondary); }.confirm-dialog footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); margin-top: var(--cs-space-24); }
</style>

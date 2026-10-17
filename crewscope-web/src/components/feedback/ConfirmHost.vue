<script setup lang="ts">
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import { useConfirm } from '../../composables/useConfirm'
const { request, settle } = useConfirm()
</script>
<template><BaseDialog :open="request !== null" role="alertdialog" :title="request?.title ?? ''" width="min(460px, 100%)" close-label="取消操作" @close="settle(false)"><p v-if="request?.description" class="confirm-host__description">{{ request.description }}</p><template #footer><!-- 危险确认的初始焦点必须落在安全侧（取消），而不是 header 的关闭钮或破坏性主钮。 --><BaseButton variant="ghost" autofocus @click="settle(false)">{{ request?.cancelLabel }}</BaseButton><BaseButton :variant="request?.danger ? 'danger' : 'primary'" @click="settle(true)">{{ request?.confirmLabel }}</BaseButton></template></BaseDialog></template>
<style scoped>
.confirm-host__description { margin: 0; color: var(--cs-text-secondary); }
</style>

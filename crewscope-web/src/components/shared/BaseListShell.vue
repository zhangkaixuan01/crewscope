<script setup lang="ts" generic="I">
import { computed, useId } from 'vue'
import { RouterLink } from 'vue-router'
import { permissions } from '../../app/auth'
import BaseButton from '../base/BaseButton.vue'
import StatePanel from '../feedback/StatePanel.vue'
import type { ErrorMessageProps, OnlineProps } from '../base/types'
import type { ResourcePhase } from './types'

/**
 * Shared list-shell skeleton for the triplets (M12b-F04): the five-level state ladder
 * (loading / forbidden / offline / error / empty), the compact strips over cached content,
 * the item list container and the load-more footer with its offline describedby pattern.
 * The filter header, list rows and the empty-state actions are domain territory — they
 * render through slots; copy enters through props.
 */
const props = defineProps<OnlineProps & ErrorMessageProps & {
  phase: ResourcePhase
  items: I[]
  nextAfter: string | null
  loadingMore: boolean
  errorStatus: number | null
  /** Section aria-label (named `label` — an `ariaLabel` prop would collide with the native attribute). */
  label: string
  loadingTitle: string
  /** M13c-W3：loading 描述层可选——title 已表达，无传入不渲染。 */
  loadingDescription?: string
  forbiddenTitle: string
  forbiddenDescription: string
  offlineEmptyTitle: string
  offlineEmptyDescription: string
  emptyTitle: string
  /** M13c-W5/W6：空态第二句可选——标题（+在场按钮）已表达，无传入不渲染。 */
  emptyDescription?: string
  compactOfflineTitle: string
  compactOfflineDescription: string
  loadMoreLabel: string
}>()

const emit = defineEmits<{
  retry: []
  loadMore: []
}>()

const initialLoading = computed(() => (props.phase === 'idle' || props.phase === 'loading') && props.items.length === 0)
const hasItems = computed(() => props.items.length > 0)
const forbidden = computed(() => props.phase === 'error' && props.errorStatus === 403)
const hardError = computed(() => props.phase === 'error' && !forbidden.value)
const offline = computed(() => !props.online)

const reasonId = useId()
</script>

<template>
  <section class="base-list-shell panel" :aria-label="label">
    <slot name="filters" />
    <slot name="lead" />

    <StatePanel v-if="initialLoading" state="loading" :title="loadingTitle" :description="loadingDescription" />
    <StatePanel v-else-if="forbidden" state="forbidden" :title="forbiddenTitle" :description="forbiddenDescription">
      <template #action>
        <RouterLink :to="{ name: 'access-denied', query: { requiredPermission: permissions.scopeRead } }">
          <BaseButton variant="secondary" size="small">查看权限说明</BaseButton>
        </RouterLink>
      </template>
    </StatePanel>
    <StatePanel v-else-if="offline && !hasItems" state="offline" :title="offlineEmptyTitle" :description="offlineEmptyDescription" />
    <StatePanel v-else-if="hardError && !hasItems" state="error" :description="errorMessage ?? undefined" @retry="emit('retry')" />
    <StatePanel v-else-if="!hasItems" state="empty" :title="emptyTitle" :description="emptyDescription">
      <template #action>
        <slot name="empty-action" />
      </template>
    </StatePanel>

    <template v-else>
      <StatePanel v-if="offline" compact state="offline" :title="compactOfflineTitle" :description="compactOfflineDescription" />
      <StatePanel v-else-if="hardError" compact state="error" :description="errorMessage ?? undefined" @retry="emit('retry')" />

      <ol class="base-list-shell__items">
        <slot name="items" />
      </ol>

      <footer v-if="nextAfter !== null" class="base-list-shell__more">
        <BaseButton
          variant="secondary"
          size="small"
          :loading="loadingMore"
          :disabled="offline"
          :aria-describedby="offline ? reasonId : undefined"
          @click="emit('loadMore')"
        >{{ loadMoreLabel }}</BaseButton>
        <!-- M13b-F12（二轮 A19）：这条说明只描述离线态——在线时也渲染是常驻噪音。 -->
        <p v-if="offline" :id="reasonId" class="base-list-shell__reason">离线时续页读取保持关闭。</p>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.base-list-shell { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); }
.base-list-shell__items { display: flex; margin: 0; padding: 0; list-style: none; flex-direction: column; gap: var(--cs-space-8); }
.base-list-shell__more { display: grid; justify-items: center; gap: var(--cs-space-4); }
.base-list-shell__reason { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>

<script setup lang="ts">
import { AlertTriangle, Ban, CircleStop, FileQuestion, LoaderCircle, RefreshCw, RotateCcw, Scale, WifiOff } from '@lucide/vue'
import { computed } from 'vue'
import BaseButton from '../base/BaseButton.vue'

const props = withDefaults(defineProps<{
  state: 'loading' | 'empty' | 'error' | 'forbidden' | 'conflict' | 'offline' | 'reconnecting' | 'recovering' | 'cancelled'
  title?: string
  description?: string
  compact?: boolean
}>(), {
  title: '',
  description: '',
  compact: false,
})

defineEmits<{ retry: [] }>()

const presentation = computed(() => ({
  loading: { icon: LoaderCircle, title: '正在加载', description: '正在获取最新团队事实。' },
  empty: { icon: FileQuestion, title: '暂无内容', description: '这里还没有可显示的工作。' },
  error: { icon: AlertTriangle, title: '加载失败', description: '服务暂时无法完成请求，请稍后重试。' },
  forbidden: { icon: Ban, title: '无权查看', description: '当前身份没有访问此范围的权限。' },
  conflict: { icon: Scale, title: '内容已发生变化', description: '其他成员更新了这份事实，请刷新后重新应用变更。' },
  offline: { icon: WifiOff, title: '当前离线', description: '已加载事实和本地草稿会保留，联网后可继续。' },
  reconnecting: { icon: RefreshCw, title: '正在重新连接', description: '正在从上次已知坐标恢复实时事实。' },
  recovering: { icon: RotateCcw, title: '执行正在恢复', description: 'Runtime 正在从最近的耐久检查点恢复执行。' },
  cancelled: { icon: CircleStop, title: '已取消', description: '本次操作已终止，已提交的业务事实保持不变。' },
})[props.state])

const busy = computed(() => props.state === 'loading' || props.state === 'reconnecting' || props.state === 'recovering')
const assertive = computed(() => props.state === 'error')
</script>

<template>
  <section class="state-panel" :class="{ compact }" :aria-busy="busy" :aria-live="assertive ? 'assertive' : 'polite'" aria-atomic="true" :role="assertive ? 'alert' : 'status'">
    <component :is="presentation.icon" :class="{ spinning: state === 'loading' || state === 'reconnecting' || state === 'recovering' }" :size="22" aria-hidden="true" />
    <div>
      <h3>{{ title || presentation.title }}</h3>
      <p>{{ description || presentation.description }}</p>
    </div>
    <BaseButton v-if="state === 'error' || state === 'conflict'" variant="secondary" size="small" @click="$emit('retry')">
      <template #icon><RefreshCw :size="14" aria-hidden="true" /></template>
      刷新事实
    </BaseButton>
    <slot name="action" />
  </section>
</template>

<style scoped>
.state-panel { display: grid; min-height: 180px; place-content: center; justify-items: center; gap: 12px; padding: 32px; color: var(--cs-text-muted); text-align: center; }
.state-panel h3 { margin-bottom: 4px; color: var(--cs-text); font-size: 15px; }
.state-panel p { max-width: 390px; margin-bottom: 0; }
.state-panel.compact { min-height: 0; grid-template-columns: auto minmax(0, 1fr) auto; place-content: initial; align-items: center; justify-items: start; gap: 9px; padding: 10px 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); text-align: left; }
.state-panel.compact h3 { margin: 0 0 2px; font-size: 10px; }
.state-panel.compact p { margin: 0; font-size: 8px; line-height: 1.45; }
.state-panel.compact > svg { width: 16px; height: 16px; }
.spinning { animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spinning { animation: none; } }
@media (max-width: 480px) { .state-panel.compact { grid-template-columns: auto minmax(0, 1fr); }.state-panel.compact > :last-child:not(div, svg) { grid-column: 1 / -1; } }
</style>

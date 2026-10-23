<script setup lang="ts">
import { AlertTriangle, Ban, CircleStop, FileQuestion, LoaderCircle, RefreshCw, RotateCcw, Scale, WifiOff } from '@lucide/vue'
import { computed, getCurrentInstance, onUpdated, ref } from 'vue'
import BaseButton from '../base/BaseButton.vue'

const props = withDefaults(defineProps<{
  state: 'loading' | 'empty' | 'error' | 'forbidden' | 'conflict' | 'offline' | 'reconnecting' | 'recovering' | 'cancelled'
  title?: string
  description?: string
  compact?: boolean
  /** Names the recovery action after the business it performs (R25);「刷新事实」 is the fallback. */
  retryLabel?: string
  /**
   * M13b-F11（C2）：error 面板必须给出恢复动作或显式承认没有。显式传 false = 此面板没有可
   * 自动重试的动作（命令失败回显、恢复动作在面板之外），渲染内置「无自动恢复」说明；不传且未接
   * @retry 的调用点由 check-state-panel-recovery 门禁拦截。offline 的恢复在网络层且 description
   * 必然说明，不参与此契约。
   */
  retryable?: boolean
}>(), {
  retryable: true,
  title: '',
  description: '',
  compact: false,
  retryLabel: '刷新事实',
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
/*
 * M13b-F11（C2）：error/offline 面板必须给出恢复动作，或显式承认没有——两个都不做就是把用户
 * 晾在死面板上。接了 @retry 的显示重试钮（offline 也能重试，此前只有 error/conflict 显示）；
 * 没接的由组件兜底渲染一行「无法自动恢复」说明，调用点不接 retry 也逃不出契约。
 */
const recoverable = computed(() => props.state === 'error' || props.state === 'conflict' || props.state === 'offline')
const recoveryStance = computed(() => props.state === 'error' && !retryWired.value && props.retryable === false)
// R25: a retry button with no recovery action behind it is noise — only render one when the caller
// actually wired @retry. Declared emits are stripped from $attrs, so read the raw vnode props —
// which are not reactive, so the update tick re-evaluates a parent flipping the handler at run
// time (current callers bind statically, but the guard must not rot into a stale first answer).
const retryTick = ref(0)
onUpdated(() => { retryTick.value += 1 })
const retryWired = computed(() => {
  retryTick.value
  return getCurrentInstance()?.vnode.props?.onRetry !== undefined
})
</script>

<template>
  <section class="state-panel" :class="{ compact }" :aria-busy="busy" :aria-live="assertive ? 'assertive' : 'polite'" aria-atomic="true" :role="assertive ? 'alert' : 'status'">
    <component :is="presentation.icon" :class="{ spinning: state === 'loading' || state === 'reconnecting' || state === 'recovering' }" :size="22" aria-hidden="true" />
    <div>
      <h3>{{ title || presentation.title }}</h3>
      <!-- The slot lets a description embed live content (e.g. a ticking 「最近同步于」 label). -->
      <p><slot name="description">{{ description || presentation.description }}</slot></p>
    </div>
    <BaseButton v-if="recoverable && retryWired" variant="secondary" size="small" @click="$emit('retry')">
      <template #icon><RefreshCw :size="14" aria-hidden="true" /></template>
      {{ retryLabel }}
    </BaseButton>
    <!-- C2：没有任何恢复动作的 error 面板必须把这一点说出来，而不是留白。 -->
    <p v-else-if="recoveryStance" class="state-panel__stance">此面板不提供自动恢复动作；请按提示处理后重试，或刷新页面重新加载。</p>
    <slot name="action" />
  </section>
</template>

<style scoped>
.state-panel { display: grid; min-height: 180px; place-content: center; justify-items: center; gap: var(--cs-space-12); padding: var(--cs-space-32); color: var(--cs-text-muted); text-align: center; }
.state-panel h3 { margin-bottom: var(--cs-space-4); color: var(--cs-text); font-size: var(--cs-text-md); }
.state-panel p { max-width: 390px; margin-bottom: 0; font-size: var(--cs-text-base); }
.state-panel.compact { min-height: 0; grid-template-columns: auto minmax(0, 1fr) auto; place-content: initial; align-items: center; justify-items: start; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); text-align: left; }
.state-panel.compact h3 { margin: 0 0 var(--cs-space-4); font-size: var(--cs-text-xs); }
.state-panel.compact p { margin: 0; font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.state-panel.compact > svg { width: var(--cs-space-16); height: var(--cs-space-16); }
.state-panel__stance { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.state-panel.compact .state-panel__stance { margin-bottom: 0; }
/* spin keyframes live once in design/base.css (M12b-Q01); the scoped name resolves there. */
.spinning { animation: spin var(--cs-motion-spin) linear infinite; }
@media (prefers-reduced-motion: reduce) { .spinning { animation: none; } }
@media (max-width: 640px) { .state-panel.compact { grid-template-columns: auto minmax(0, 1fr); }.state-panel.compact > :last-child:not(div, svg) { grid-column: 1 / -1; } }
</style>

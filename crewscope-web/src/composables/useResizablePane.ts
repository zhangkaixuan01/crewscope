import { computed, type Ref } from 'vue'
import { usePreference } from './usePreference'

export interface ResizablePaneOptions {
  min?: number
  max?: number
  step?: number
  /**
   * Which screen edge the pane sits against. A pane on the right grows when its divider moves
   * *toward the centre* — visually left — so pointer and keyboard deltas flip sign with the side.
   */
  side?: 'left' | 'right'
}

interface PanePreference {
  ratio: number
  collapsed: boolean
}

/**
 * Provides an accessible pointer/keyboard resizer whose pane width survives reloads.
 * M14 B1: `ratio` is a pixel width, not a container percentage — fixed gutters read truer in a
 * docked two-pane layout, and a stored preference stays stable across viewport widths. The sole
 * caller bumped its preference key (v2→v3) so legacy percent values fall back to the default.
 */
export function useResizablePane(
  key: string,
  fallback: number,
  options: ResizablePaneOptions = {},
): {
  ratio: Ref<number>
  collapsed: Ref<boolean>
  toggle: () => void
  startResize: (event: PointerEvent) => void
  handleKeydown: (event: KeyboardEvent) => void
} {
  const min = options.min ?? 240
  const max = options.max ?? 420
  const step = options.step ?? 16
  const direction = (options.side ?? 'left') === 'right' ? -1 : 1
  const defaultPreference: PanePreference = { ratio: clamp(fallback, min, max), collapsed: false }
  const preference = usePreference(key, defaultPreference, {
    version: 1,
    // 读侧结构校验（R28）：null、旧 schema 或被改坏的存储回退安全布局，而不是让
    // `.ratio` 在渲染路径上抛 TypeError。
    validate: (value): value is PanePreference => {
      if (value == null || typeof value !== 'object' || Array.isArray(value)) return false
      const candidate = value as Partial<PanePreference>
      return typeof candidate.ratio === 'number' && Number.isFinite(candidate.ratio)
        && typeof candidate.collapsed === 'boolean'
    },
  })
  const ratio = computed({
    get: () => clamp(preference.value.value.ratio, min, max),
    set: value => { preference.value.value = { ...preference.value.value, ratio: clamp(value, min, max) } },
  })
  const collapsed = computed({
    get: () => preference.value.value.collapsed,
    set: value => { preference.value.value = { ...preference.value.value, collapsed: value } },
  })

  function toggle(): void { collapsed.value = !collapsed.value }

  function startResize(event: PointerEvent): void {
    if (collapsed.value) return
    event.preventDefault()
    const startX = event.clientX
    const startRatio = ratio.value
    const move = (moveEvent: PointerEvent) => {
      ratio.value = startRatio + direction * (moveEvent.clientX - startX)
    }
    const stop = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', stop)
      window.removeEventListener('pointercancel', stop)
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', stop, { once: true })
    window.addEventListener('pointercancel', stop, { once: true })
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault()
      // 方向键与所在边界一致（R28）：靠右的面板「向右」是收窄，靠左的是放宽。
      ratio.value += direction * (event.key === 'ArrowRight' ? step : -step)
      return
    }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      toggle()
      return
    }
    if (event.key === 'Home') {
      event.preventDefault()
      preference.reset()
    }
  }

  return { ratio, collapsed, toggle, startResize, handleKeydown }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, Number.isFinite(value) ? value : min))
}

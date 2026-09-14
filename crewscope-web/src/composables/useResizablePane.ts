import { computed, type Ref } from 'vue'
import { usePreference } from './usePreference'

export interface ResizablePaneOptions {
  min?: number
  max?: number
  step?: number
}

/** Provides an accessible pointer/keyboard resizer whose ratio survives reloads. */
export function useResizablePane(
  key: string,
  fallback: number,
  options: ResizablePaneOptions = {},
): {
  ratio: Ref<number>
  collapsed: Ref<boolean>
  toggle: () => void
  startResize: (event: PointerEvent, container: HTMLElement) => void
  handleKeydown: (event: KeyboardEvent) => void
} {
  const min = options.min ?? 18
  const max = options.max ?? 48
  const step = options.step ?? 2
  const preference = usePreference(key, { ratio: clamp(fallback, min, max), collapsed: false }, { version: 1 })
  const ratio = computed({
    get: () => clamp(preference.value.value.ratio, min, max),
    set: value => { preference.value.value = { ...preference.value.value, ratio: clamp(value, min, max) } },
  })
  const collapsed = computed({
    get: () => preference.value.value.collapsed,
    set: value => { preference.value.value = { ...preference.value.value, collapsed: value } },
  })

  function toggle(): void { collapsed.value = !collapsed.value }

  function startResize(event: PointerEvent, container: HTMLElement): void {
    if (collapsed.value) return
    event.preventDefault()
    const startX = event.clientX
    const startRatio = ratio.value
    const width = container.getBoundingClientRect().width
    const move = (moveEvent: PointerEvent) => {
      if (width <= 0) return
      ratio.value = startRatio + ((moveEvent.clientX - startX) / width) * 100
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
    if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return
    event.preventDefault()
    ratio.value += event.key === 'ArrowRight' ? step : -step
  }

  return { ratio, collapsed, toggle, startResize, handleKeydown }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, Number.isFinite(value) ? value : min))
}

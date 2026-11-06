import { describe, expect, it, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { useResizablePane } from './useResizablePane'

describe('useResizablePane', () => {
  beforeEach(() => localStorage.clear())

  it('clamps keyboard resizing and persists collapse state', async () => {
    // M14 B1：ratio 语义为像素宽度。
    const pane = useResizablePane('pane', 280, { min: 240, max: 420, step: 16 })
    pane.handleKeydown(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    expect(pane.ratio.value).toBe(296)
    pane.handleKeydown(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    expect(pane.ratio.value).toBe(312)
    pane.handleKeydown(new KeyboardEvent('keydown', { key: 'ArrowLeft' }))
    pane.handleKeydown(new KeyboardEvent('keydown', { key: 'ArrowLeft' }))
    pane.handleKeydown(new KeyboardEvent('keydown', { key: 'ArrowLeft' }))
    expect(pane.ratio.value).toBe(264)
    pane.toggle()
    await nextTick()
    expect(JSON.parse(localStorage.getItem('pane')!).value.collapsed).toBe(true)
  })

  it('moves a right-edge pane opposite to a left-edge one, with pointer and keyboard agreeing', () => {
    // R28：右栏贴右边界，分隔条向左拖才是放宽；方向键遵循同一个几何。
    const leftPane = useResizablePane('left', 280, { min: 240, max: 420, step: 16, side: 'left' })
    const rightPane = useResizablePane('right', 300, { min: 240, max: 420, step: 16, side: 'right' })

    leftPane.startResize(new PointerEvent('pointerdown', { clientX: 200 }))
    window.dispatchEvent(new PointerEvent('pointermove', { clientX: 260 }))
    window.dispatchEvent(new PointerEvent('pointerup'))
    expect(leftPane.ratio.value).toBe(340)

    rightPane.startResize(new PointerEvent('pointerdown', { clientX: 800 }))
    window.dispatchEvent(new PointerEvent('pointermove', { clientX: 740 }))
    window.dispatchEvent(new PointerEvent('pointerup'))
    expect(rightPane.ratio.value).toBe(360)

    leftPane.handleKeydown(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    rightPane.handleKeydown(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    expect(leftPane.ratio.value).toBe(356)
    expect(rightPane.ratio.value).toBe(344)
  })

  it('folds from the keyboard and resets to the default width from Home', async () => {
    const pane = useResizablePane('fold', 280, { min: 240, max: 420, step: 16 })

    pane.handleKeydown(new KeyboardEvent('keydown', { key: 'Enter' }))
    expect(pane.collapsed.value).toBe(true)
    pane.handleKeydown(new KeyboardEvent('keydown', { key: ' ' }))
    expect(pane.collapsed.value).toBe(false)

    pane.handleKeydown(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    pane.handleKeydown(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    expect(pane.ratio.value).toBe(312)
    pane.handleKeydown(new KeyboardEvent('keydown', { key: 'Home' }))
    expect(pane.ratio.value).toBe(280)
    await nextTick()
    expect(JSON.parse(localStorage.getItem('fold')!).value).toEqual({ ratio: 280, collapsed: false })
  })

  it('falls back to the safe layout when the stored preference is null, legacy, or corrupt', () => {
    localStorage.setItem('corrupt-a', JSON.stringify({ version: 1, value: null }))
    localStorage.setItem('corrupt-b', JSON.stringify({ version: 1, value: { ratio: 'wide', collapsed: 'yes' } }))
    localStorage.setItem('corrupt-c', JSON.stringify({ version: 1, value: { ratio: 280 } }))
    localStorage.setItem('legacy', JSON.stringify({ version: 9, value: { ratio: 22, collapsed: true } }))

    for (const key of ['corrupt-a', 'corrupt-b', 'corrupt-c', 'legacy']) {
      const pane = useResizablePane(key, 280, { min: 240, max: 420 })
      expect(pane.ratio.value).toBe(280)
      expect(pane.collapsed.value).toBe(false)
    }
  })
})

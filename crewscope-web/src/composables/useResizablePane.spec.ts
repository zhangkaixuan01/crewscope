import { describe, expect, it, beforeEach } from 'vitest'
import { nextTick, ref } from 'vue'
import { useResizablePane } from './useResizablePane'

describe('useResizablePane', () => {
  beforeEach(() => localStorage.clear())

  it('clamps keyboard resizing and persists collapse state', async () => {
    const pane = useResizablePane('pane', 24, { min: 20, max: 30, step: 4 })
    pane.handleKeydown(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    expect(pane.ratio.value).toBe(28)
    pane.handleKeydown(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    expect(pane.ratio.value).toBe(30)
    pane.toggle()
    await nextTick()
    expect(JSON.parse(localStorage.getItem('pane')!).value.collapsed).toBe(true)
  })
})

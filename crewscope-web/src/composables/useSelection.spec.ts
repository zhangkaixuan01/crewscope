import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { useSelection } from './useSelection'

describe('useSelection', () => {
  it('retains selection across pages and enforces the upper bound', () => {
    const page = ref([{ id: 'a' }, { id: 'b' }])
    const selection = useSelection({ maxSelected: 2, items: page })
    expect(selection.selectPage(page.value)).toBe(2)
    expect(selection.select('c')).toBe(false)
    page.value = [{ id: 'c' }]
    expect(selection.selectedCount.value).toBe(2)
    expect(selection.selectedItems.value).toEqual([])
    selection.deselect('a')
    expect(selection.select('c')).toBe(true)
    expect(selection.selectedItems.value).toEqual([{ id: 'c' }])
  })
})


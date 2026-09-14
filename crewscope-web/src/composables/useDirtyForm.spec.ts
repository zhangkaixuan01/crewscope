import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useDirtyForm } from './useDirtyForm'

describe('useDirtyForm', () => {
  it('tracks changes and restores a scoped draft', () => {
    const value = ref({ name: 'initial' })
    const form = useDirtyForm(value, { draftKey: 'test:dirty-form' })
    expect(form.isDirty.value).toBe(false)
    value.value.name = 'changed'
    form.sync(value.value)
    expect(form.isDirty.value).toBe(true)
    form.saveDraft()
    expect(form.restoreDraft()).toEqual({ name: 'changed' })
    form.markClean(value.value)
    expect(form.isDirty.value).toBe(false)
    form.clearDraft()
    expect(form.restoreDraft()).toBeNull()
  })

  it('requests confirmation before leaving a dirty form', async () => {
    const value = ref({ name: 'initial' })
    const form = useDirtyForm(value)
    form.markDirty()
    const event = { preventDefault: vi.fn(), returnValue: '' } as unknown as BeforeUnloadEvent
    form.handleBeforeUnload(event)
    expect(event.preventDefault).toHaveBeenCalled()
    expect(event.returnValue).toBe('')
  })
})

import { afterEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { activateF05Identity, F05_EPOCH_KEY, F05_USER_PREFIX } from '../app/f05Storage'
import { useDirtyForm } from './useDirtyForm'

const scope = { accountId: 'account-1', principalId: 'principal-1', organizationId: 'org-1', teamId: 'team-1', projectId: null, objectId: 'agent-1' }

describe('useDirtyForm', () => {
  afterEach(() => { localStorage.clear() })

  it('tracks changes and restores a scoped draft', () => {
    activateF05Identity('account-1')
    const value = ref({ name: 'initial' })
    const form = useDirtyForm(value, { draftScope: scope })
    expect(form.isDirty.value).toBe(false)
    value.value.name = 'changed'
    form.sync(value.value)
    expect(form.isDirty.value).toBe(true)
    const saved = form.saveDraft()
    expect(saved.ok).toBe(true)
    expect(form.restoreDraft()).toEqual({ name: 'changed' })
    form.markClean(value.value)
    expect(form.isDirty.value).toBe(false)
    form.clearDraft()
    expect(form.restoreDraft()).toBeNull()
  })

  it('reports a failed write instead of implying the draft was saved', () => {
    const value = ref({ name: 'initial' })
    // No activated identity: the namespace stays sealed, so the write must fail visibly.
    const form = useDirtyForm(value, { draftScope: scope })
    value.value.name = 'changed'
    form.sync(value.value)
    const saved = form.saveDraft()
    expect(saved.ok).toBe(false)
    expect(saved.reason).toBe('stale-epoch')
    expect(form.restoreDraft()).toBeNull()
  })

  it('keeps drafts inside the scoped namespace only', () => {
    activateF05Identity('account-1')
    const value = ref({ name: 'initial' })
    const form = useDirtyForm(value, { draftScope: scope })
    form.markDirty()
    form.saveDraft()
    expect(Object.keys(localStorage).every(key => key.startsWith(F05_USER_PREFIX) || key === F05_EPOCH_KEY)).toBe(true)
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

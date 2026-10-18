import { defineComponent, h, nextTick, reactive, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { useInlineSettingEditor } from './useInlineSettingEditor'

interface Harness {
  editor: ReturnType<typeof useInlineSettingEditor<number | null>>
  fields: { current: string }
  state: { phase: string, serverValue: number | null, savedKeys: string[] }
  setServerValue: (value: number | null) => void
  setPhase: (phase: string) => void
  inputs: { value: number | null }
}

/** Number-in-string wiring, the shape both settings pages use for their numeric fields. */
async function mountEditor(overrides: Record<string, unknown> = {}): Promise<Harness> {
  const inputs = ref<number | null>(90)
  const fields = reactive({ current: '90' })
  const state = reactive({ phase: 'idle', serverValue: 90 as number | null, savedKeys: [] as string[] })
  const saveResult = ref(true)
  let editor!: ReturnType<typeof useInlineSettingEditor<number | null>>
  mount(defineComponent({
    setup() {
      editor = useInlineSettingEditor<number | null>({
        payload: () => (fields.current.trim() === '' ? null : Number.parseInt(fields.current, 10)),
        serverValue: () => state.serverValue,
        applyServerValue: value => {
          inputs.value = value
          fields.current = value === null ? '' : String(value)
        },
        valid: () => fields.current.trim() === '' || Number.parseInt(fields.current, 10) >= 30,
        ready: () => true,
        phase: () => state.phase,
        clearCommand: () => { state.phase = 'idle' },
        save: async key => {
          state.savedKeys.push(key)
          return saveResult.value
        },
        ...overrides,
      })
      return () => h('div')
    },
  }))
  return {
    editor,
    fields,
    state,
    inputs,
    setServerValue: value => { state.serverValue = value },
    setPhase: phase => { state.phase = phase },
  }
}

describe('useInlineSettingEditor', () => {
  it('starts editing from the server value, tracks edits as dirty and cancels back', async () => {
    const harness = await mountEditor()
    harness.editor.startEditing()
    expect(harness.editor.editing.value).toBe(true)
    expect(harness.state.phase).toBe('idle')
    expect(harness.inputs.value).toBe(90)
    harness.fields.current = '120'
    await nextTick()
    expect(harness.editor.dirtyForm.dirty.value).toBe(true)
    harness.editor.cancelEditing()
    expect(harness.editor.editing.value).toBe(false)
    expect(harness.fields.current).toBe('90')
    expect(harness.editor.dirtyForm.dirty.value).toBe(false)
  })

  it('keeps the attempt key for a failed send and reuses it for the unchanged retry', async () => {
    const harness = await mountEditor({ save: async (key: string) => { harness.state.savedKeys.push(key); return false } })
    harness.editor.startEditing()
    harness.fields.current = '120'
    await harness.editor.submit()
    await harness.editor.submit()
    expect(harness.state.savedKeys).toHaveLength(2)
    expect(harness.state.savedKeys[1]).toBe(harness.state.savedKeys[0])
  })

  it('exits the editor and re-baselines when the store confirms the save', async () => {
    const harness = await mountEditor({
      save: async (key: string) => {
        harness.state.savedKeys.push(key)
        harness.setServerValue(120)
        harness.setPhase('success')
        return true
      },
    })
    harness.editor.startEditing()
    harness.fields.current = '120'
    await harness.editor.submit()
    await nextTick()
    expect(harness.editor.editing.value).toBe(false)
    expect(harness.editor.dirtyForm.dirty.value).toBe(false)
    // The serverValue watch resets the fields once editing has exited.
    expect(harness.fields.current).toBe('120')
  })

  it('gates the submit on validation and drops the key after a rejected send', async () => {
    const harness = await mountEditor()
    harness.editor.startEditing()
    harness.fields.current = '7'
    await harness.editor.submit()
    expect(harness.state.savedKeys).toHaveLength(0)
    expect(harness.editor.submitted.value).toBe(true)
    harness.fields.current = '120'
    await harness.editor.submit()
    expect(harness.state.savedKeys).toHaveLength(1)
    const first = harness.state.savedKeys[0]
    harness.fields.current = '180'
    await harness.editor.submit()
    // A different payload with a rejected send mints a fresh key each time.
    expect(harness.state.savedKeys[1]).not.toBe(first)
  })

  it('resets the fields from the server while not editing and leaves them alone mid-edit', async () => {
    const harness = await mountEditor()
    harness.setServerValue(45)
    await nextTick()
    expect(harness.fields.current).toBe('45')
    harness.editor.startEditing()
    harness.fields.current = '120'
    harness.setServerValue(60)
    await nextTick()
    expect(harness.fields.current).toBe('120')
    expect(harness.editor.dirtyForm.dirty.value).toBe(true)
  })
})

import { mount } from '@vue/test-utils'
import SkillCreateDialog from './SkillCreateDialog.vue'

describe('SkillCreateDialog', () => {
  it('seeds the document template while the skillKey is being typed', async () => {
    const wrapper = mounted()

    await wrapper.get('input').setValue('deploy-runbook-v2')

    const textarea = wrapper.get('textarea')
    expect((textarea.element as HTMLTextAreaElement).value).toContain('name: deploy-runbook-v2')
  })

  it('rejects the reserved built-in key client-side', async () => {
    const wrapper = mounted()
    await fillValidKey(wrapper, 'java-spring-v1')

    await submit(wrapper)

    expect(wrapper.text()).toContain('保留名')
    expect(wrapper.emitted('create')).toBeUndefined()
  })

  it('rejects a key that violates the skill pattern before any request', async () => {
    const wrapper = mounted()
    await fillValidKey(wrapper, 'Deploy_Runbook')

    await submit(wrapper)

    expect(wrapper.text()).toContain('skillKey 需要 1-63 个字符')
    expect(wrapper.emitted('create')).toBeUndefined()
  })

  it('rejects a document whose name drifts from the skillKey', async () => {
    const wrapper = mounted()
    await fillValidKey(wrapper, 'deploy-runbook-v2')
    await wrapper.get('textarea').setValue('---\nname: other-key\ndescription: d\n---\n\nbody')

    await submit(wrapper)

    expect(wrapper.text()).toContain('frontmatter 的 name 必须与 skillKey 完全一致')
    expect(wrapper.emitted('create')).toBeUndefined()
  })

  it('emits the create command once key and document both validate', async () => {
    const wrapper = mounted()
    await fillValidKey(wrapper, 'deploy-runbook-v2')

    await submit(wrapper)

    const [input, key] = wrapper.emitted('create')![0]! as [{ skillKey: string, content: string }, string]
    expect(input.skillKey).toBe('deploy-runbook-v2')
    expect(input.content).toContain('name: deploy-runbook-v2')
    expect(typeof key).toBe('string')
  })

  it('shows the server-side command error without losing the form', async () => {
    const wrapper = mount(SkillCreateDialog, {
      props: { teamId: 'team-1', submitting: false, errorMessage: 'skillKey 已存在' },
      attachTo: document.body,
    })
    await fillValidKey(wrapper, 'deploy-runbook-v2')

    expect(wrapper.text()).toContain('skillKey 已存在')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toContain('name: deploy-runbook-v2')
    wrapper.unmount()
  })
})

function mounted() {
  return mount(SkillCreateDialog, {
    props: { teamId: 'team-1', submitting: false, errorMessage: null },
    attachTo: document.body,
  })
}

async function fillValidKey(wrapper: ReturnType<typeof mounted>, key: string) {
  await wrapper.get('input').setValue(key)
}

async function submit(wrapper: ReturnType<typeof mounted>) {
  await wrapper.get('form').trigger('submit')
}

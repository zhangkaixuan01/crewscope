import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ModelOptionPicker from './ModelOptionPicker.vue'
import type { SelectableAgentModel } from '../../domains/agent/types'

const model = (name: string, provider: string): SelectableAgentModel => ({
  connectionId: `${provider}-connection`, connectionOwnerType: 'USER', connectionOwnerId: 'member', providerKey: provider,
  providerDisplayName: provider, catalogEntryId: name, modelId: name, catalogRevision: 1, modelDisplayName: name,
  region: 'cn-hangzhou', contextWindowTokens: 8192, maximumOutputTokens: 4096, capabilities: ['text'],
  price: { inputPerMillionTokens: '1', outputPerMillionTokens: '2', cachedInputPerMillionTokens: null, currencyCode: 'USD' },
})

describe('ModelOptionPicker', () => {
  it('groups models and filters by search text', async () => {
    const wrapper = mount(ModelOptionPicker, { props: { models: [model('deepseek-v4', 'DeepSeek'), model('gpt-5', 'OpenAI')], modelValue: '' } })
    await wrapper.get('.model-picker__trigger').trigger('click')
    expect(wrapper.text()).toContain('DeepSeek')
    await wrapper.get('input[type="search"]').setValue('gpt')
    expect(wrapper.text()).toContain('gpt-5')
    expect(wrapper.text()).not.toContain('deepseek-v4')
  })
})

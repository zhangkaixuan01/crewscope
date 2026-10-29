import { describe, expect, it } from 'vitest'
import { agentIdentityLabel, agentOwnershipRoleLabel } from './labels'

// 真人走查判例：ownership 前缀与 role 词首重叠时直接折叠，USER+PERSONAL_ASSISTANT
// 曾渲染成「个人个人助理」。词表新增条目须保持无重叠或自然折叠。
describe('agentOwnershipRoleLabel folds the redundant ownership prefix', () => {
  it.each([
    ['USER', 'PERSONAL_ASSISTANT', '个人助理'],
    ['TEAM', 'TEAM_COORDINATOR', '团队协调者'],
    ['USER', 'SPECIALIST', '个人Specialist'],
    ['TEAM', 'SPECIALIST', '团队Specialist'],
    ['ORGANIZATION', 'SPECIALIST', '组织Specialist'],
  ])('%s + %s renders as %s', (ownership, role, expected) => {
    expect(agentOwnershipRoleLabel(ownership, role)).toBe(expected)
  })

  it('keeps the displayName first in the picker identity line', () => {
    expect(agentIdentityLabel({ displayName: '走查测试 · Personal Agent', ownershipType: 'USER', runtimeRole: 'PERSONAL_ASSISTANT' }))
      .toBe('走查测试 · Personal Agent · 个人助理')
  })
})

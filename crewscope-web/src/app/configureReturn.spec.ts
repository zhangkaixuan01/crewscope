import { describe, expect, it } from 'vitest'
import { applyConfigureReturn, buildConfigureReturn } from './configureReturn'

const TEAM = '00000000-0000-4000-8000-000000000001'
const PROJECT = '00000000-0000-4000-8000-000000000002'
const WORK_ITEM = '00000000-0000-4000-8000-000000000003'

describe('buildConfigureReturn', () => {
  it('carries the registered origin plus its whitelisted uuid coordinates', () => {
    expect(buildConfigureReturn('work', { team: TEAM, project: PROJECT, workItem: WORK_ITEM }))
      .toEqual({ from: 'work', team: TEAM, project: PROJECT, workItem: WORK_ITEM })
  })

  it('drops non-uuid values instead of letting arbitrary text into the URL', () => {
    expect(buildConfigureReturn('work', { team: TEAM, workItem: 'draft 文本 <script>', project: '' }))
      .toEqual({ from: 'work', team: TEAM })
  })

  it('drops coordinates the origin never registered', () => {
    // setup 现在合法携带 project（OPEN_EXECUTION_DEFAULTS 的落点坐标），用 work 验证裁剪语义。
    expect(buildConfigureReturn('work', { team: TEAM, project: PROJECT, conversation: PROJECT }))
      .toEqual({ from: 'work', team: TEAM, project: PROJECT })
    expect(buildConfigureReturn('setup', { team: TEAM, workItem: WORK_ITEM }))
      .toEqual({ from: 'setup', team: TEAM })
  })

  it('returns an empty query for unregistered origins', () => {
    expect(buildConfigureReturn('https://evil.example', { team: TEAM })).toEqual({})
    expect(buildConfigureReturn('not-a-route', {})).toEqual({})
  })

  it('supports origins without coordinates', () => {
    expect(buildConfigureReturn('onboarding', {})).toEqual({ from: 'onboarding' })
  })
})

describe('applyConfigureReturn', () => {
  it('restores a registered origin with validated coordinates', () => {
    expect(applyConfigureReturn({ from: 'work', team: TEAM, project: PROJECT, workItem: WORK_ITEM }))
      .toEqual({ routeName: 'work', label: '返回工作项', query: { team: TEAM, project: PROJECT, workItem: WORK_ITEM } })
  })

  it('ignores absent, unregistered and malformed origins', () => {
    expect(applyConfigureReturn({})).toBeNull()
    expect(applyConfigureReturn({ from: 'evil.example/path' })).toBeNull()
    expect(applyConfigureReturn({ from: 'x'.repeat(64) })).toBeNull()
  })

  it('drops invalid coordinate values but still returns to the origin', () => {
    expect(applyConfigureReturn({ from: 'today', team: 'not-a-uuid' }))
      .toEqual({ routeName: 'today', label: '返回今日工作', query: {} })
  })

  it('rejects array and empty coordinate values', () => {
    expect(applyConfigureReturn({ from: 'work', team: [TEAM, PROJECT], workItem: '' })?.query)
      .toEqual({})
  })

  it('restores the one-shot delegate key only with its whitelisted value', () => {
    expect(applyConfigureReturn({ from: 'work', team: TEAM, delegate: 'coding' })?.query)
      .toEqual({ team: TEAM, delegate: 'coding' })
    expect(applyConfigureReturn({ from: 'work', team: TEAM, delegate: 'other' })?.query)
      .toEqual({ team: TEAM })
  })

  it('never restores one-shot keys for origins that did not register them', () => {
    expect(applyConfigureReturn({ from: 'setup', team: TEAM, delegate: 'coding' })?.query)
      .toEqual({ team: TEAM })
  })
})

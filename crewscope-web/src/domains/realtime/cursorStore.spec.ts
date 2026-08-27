import { createThreeStreamCursorStore } from './cursorStore'

describe('ThreeStreamCursorStore', () => {
  beforeEach(() => localStorage.clear())

  it('partitions durable Team and Conversation Cursors by their complete Scope', () => {
    const store = createThreeStreamCursorStore(localStorage)
    const teamA = { organizationId: 'org-1', teamId: 'team-a' }
    const teamB = { organizationId: 'org-1', teamId: 'team-b' }
    const conversationA = { ...teamA, conversationId: 'conversation-a' }
    const conversationB = { ...teamA, conversationId: 'conversation-b' }

    store.saveDurableCursor('TEAM', teamA, 'team-a-cursor')
    store.saveDurableCursor('TEAM', teamB, 'team-b-cursor')
    store.saveDurableCursor('CONVERSATION', conversationA, 'conversation-a-cursor')
    store.saveDurableCursor('CONVERSATION', conversationB, 'conversation-b-cursor')

    expect(store.getDurableCursor('TEAM', teamA)).toBe('team-a-cursor')
    expect(store.getDurableCursor('TEAM', teamB)).toBe('team-b-cursor')
    expect(store.getDurableCursor('CONVERSATION', conversationA)).toBe('conversation-a-cursor')
    expect(store.getDurableCursor('CONVERSATION', conversationB)).toBe('conversation-b-cursor')
  })

  it('stores AG-UI invocation coordinates without treating an SSE id as a durable Cursor', () => {
    const store = createThreeStreamCursorStore(localStorage)
    const scope = { organizationId: 'org-1', teamId: 'team-a', conversationId: 'conversation-a' }

    store.saveAgUiResume(scope, { invocationId: 'invocation-1', idempotencyKey: 'command-1', eventOffset: 7 })

    expect(store.getAgUiResume(scope)).toEqual({ invocationId: 'invocation-1', idempotencyKey: 'command-1', eventOffset: 7 })
    expect(store.getDurableCursor('CONVERSATION', scope)).toBeNull()
    expect([...Array(localStorage.length)].map((_, index) => localStorage.key(index))).not.toContain(expect.stringContaining('durable:AG_UI'))
  })

  it('removes corrupt state and clears only the requested Team Scope', () => {
    const store = createThreeStreamCursorStore(localStorage)
    const teamA = { organizationId: 'org-1', teamId: 'team-a' }
    const teamB = { organizationId: 'org-1', teamId: 'team-b' }
    store.saveDurableCursor('TEAM', teamA, 'team-a-cursor')
    store.saveDurableCursor('TEAM', teamB, 'team-b-cursor')
    const teamAKey = [...Array(localStorage.length)].map((_, index) => localStorage.key(index)).find(key => key?.includes('team-a'))!
    localStorage.setItem(teamAKey, '{broken')

    expect(store.getDurableCursor('TEAM', teamA)).toBeNull()
    expect(localStorage.getItem(teamAKey)).toBeNull()
    store.clearScope(teamB)
    expect(store.getDurableCursor('TEAM', teamB)).toBeNull()
  })

  it('requires conversation identity for Conversation and AG-UI recovery', () => {
    const store = createThreeStreamCursorStore(localStorage)
    const scope = { organizationId: 'org-1', teamId: 'team-a' }

    expect(() => store.getDurableCursor('CONVERSATION', scope)).toThrow('conversationId')
    expect(() => store.getAgUiResume(scope)).toThrow('conversationId')
  })
})

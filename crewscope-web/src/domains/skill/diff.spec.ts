import { diffLines } from './diff'

describe('diffLines', () => {
  it('returns all-same lines with zero counts on an identical pair', () => {
    const diff = diffLines('---\nname: k\n---\nbody', '---\nname: k\n---\nbody')

    expect(diff.collapsed).toBe(false)
    expect(diff.addedCount).toBe(0)
    expect(diff.removedCount).toBe(0)
    expect(diff.lines.map(line => line.type)).toEqual(['same', 'same', 'same', 'same'])
  })

  it('reports a pure insertion and a pure deletion', () => {
    const added = diffLines('a\nb', 'a\nb\nc')
    expect(added.lines.map(line => line.type)).toEqual(['same', 'same', 'added'])
    expect(added.addedCount).toBe(1)
    expect(added.removedCount).toBe(0)

    const removed = diffLines('a\nb\nc', 'a\nc')
    expect(removed.lines.map(line => line.type)).toEqual(['same', 'removed', 'same'])
    expect(removed.removedCount).toBe(1)
  })

  it('pairs a replaced line as removed plus added, keeping the minimal edit script', () => {
    const diff = diffLines('alpha\nbeta\ngamma', 'alpha\nBETA\ngamma')

    expect(diff.lines.map(line => line.type)).toEqual(['same', 'removed', 'added', 'same'])
    expect(diff.lines[1]?.text).toBe('beta')
    expect(diff.lines[2]?.text).toBe('BETA')
  })

  it('keeps a trailing-newline change visible as one line', () => {
    const diff = diffLines('body', 'body\n')

    expect(diff.lines.map(line => line.type)).toEqual(['same', 'added'])
    expect(diff.addedCount).toBe(1)
  })

  it('collapses to an all-changed report when the pair exceeds the LCS cell budget', () => {
    // 1001 × 1001 distinct lines blow past the 1,000,000-cell cap: LCS is skipped, not frozen.
    const before = Array.from({ length: 1001 }, (_unused, index) => `before-${index}`).join('\n')
    const after = Array.from({ length: 1001 }, (_unused, index) => `after-${index}`).join('\n')

    const diff = diffLines(before, after)

    expect(diff.collapsed).toBe(true)
    expect(diff.removedCount).toBe(1001)
    expect(diff.addedCount).toBe(1001)
    expect(diff.lines[0]?.type).toBe('removed')
    expect(diff.lines.at(-1)?.type).toBe('added')
  })
})

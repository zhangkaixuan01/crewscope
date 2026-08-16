import fixtureJson from './fixtures/diff-stream-v1.json'
import {
  DiffProjection,
  compareUnicodeCodePoints,
  parseDiffStreamFixture,
  replayDiffFixture,
  type DiffStreamEvent,
} from './diffProjection'

describe('M4-S03 Diff projection protocol', () => {
  const fixture = parseDiffStreamFixture(fixtureJson)

  it('converges after out-of-order, duplicate and reset delivery', () => {
    const result = replayDiffFixture(fixture)

    expect(result.outcomes).toEqual(fixture.expectedOutcomes)
    expect(result.projection.generation).toBe(fixture.expectedProjection.generation)
    expect(result.projection.cursor).toBe(fixture.expectedProjection.cursor)
    expect(result.projection.manifestHash).toBe(fixture.expectedProjection.manifestHash)
    expect(result.projection.files.map(file => file.path)).toEqual(fixture.expectedProjection.paths)
    expect(result.projection.waitingForReset).toBe(false)
    expect(compareUnicodeCodePoints('\uE000', '\u{10000}')).toBeLessThan(0)
  })

  it('does not mutate the last complete state when a future delta exposes a gap', () => {
    const projection = new DiffProjection(fixture.workspaceId, fixture.streamEpoch)
    const [initial, , future] = fixture.events

    expect(projection.apply(initial!)).toBe('RESET')
    const before = projection.snapshot()
    expect(projection.apply(future!)).toBe('GAP')
    expect(projection.snapshot()).toEqual({ ...before, waitingForReset: true })
  })

  it('accepts a reset across a sequence gap and keeps the cursor opaque', () => {
    const projection = new DiffProjection(fixture.workspaceId, fixture.streamEpoch)
    const initial = fixture.events[0]!
    const reset = fixture.events.at(-1)!

    projection.apply(initial)
    expect(projection.apply(reset)).toBe('RESET')
    expect(projection.snapshot().cursor).toBe(reset.cursor)
    expect(reset.cursor).not.toContain(fixture.workspaceId)
  })

  it('rejects scope mixing and malformed shared fixtures', () => {
    const projection = new DiffProjection(fixture.workspaceId, fixture.streamEpoch)
    expect(() => projection.apply(fixture.events[0]!, crypto.randomUUID(), fixture.streamEpoch))
      .toThrow('scope does not match')

    const malformed = structuredClone(fixtureJson) as unknown as { events: DiffStreamEvent[] }
    malformed.events[0]!.files[0]!.path = '../outside'
    expect(() => parseDiffStreamFixture(malformed)).toThrow('Invalid or duplicate Diff path')

    const conflicting = structuredClone(fixtureJson) as unknown as { events: DiffStreamEvent[] }
    conflicting.events[0]!.removals = [conflicting.events[0]!.files[0]!.path]
    expect(() => parseDiffStreamFixture(conflicting)).toThrow('conflicting Diff removal')
  })
})

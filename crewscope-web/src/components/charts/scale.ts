/**
 * Shared deterministic scale math for the chart primitives. Charts must render
 * identically for identical data (no randomness, no animation), so the ceiling
 * is a pure function: the value rounded up to the next 1/2/5×10ⁿ step.
 */
export function niceCeiling(value: number): number {
  if (!Number.isFinite(value) || value <= 0) return 1
  const base = 10 ** Math.floor(Math.log10(value))
  const scaled = value / base
  const step = scaled <= 1 ? 1 : scaled <= 2 ? 2 : scaled <= 5 ? 5 : 10
  return step * base
}

/** The four gridline values of a [0, ceiling] domain, bottom to top. */
export function gridTicks(ceiling: number): number[] {
  return [0, 0.25, 0.5, 0.75, 1].map(fraction => fraction * ceiling)
}

/** Category-label thinning: at most `max` labels, always including the last point. */
export function thinnedIndexes(count: number, max: number): number[] {
  if (count <= 0) return []
  const step = Math.max(1, Math.ceil(count / max))
  const indexes: number[] = []
  for (let index = 0; index < count; index += step) indexes.push(index)
  if (indexes[indexes.length - 1] !== count - 1) indexes.push(count - 1)
  return indexes
}

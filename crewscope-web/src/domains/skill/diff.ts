/**
 * Line-level diff for the skill draft editor and version history (F02 D3): a single unified
 * column — a side-by-side split is unreadable at the 390px narrow breakpoint, and a rollback
 * whose before/after are byte-identical must render as "all same" without ceremony.
 * Zero dependencies; LCS with a hard budget so a pathological paste cannot freeze the tab.
 */
export type DiffLineType = 'same' | 'added' | 'removed'

export interface DiffLine {
  type: DiffLineType
  text: string
}

export interface LinesDiff {
  lines: DiffLine[]
  addedCount: number
  removedCount: number
  /**
   * True when the pair exceeded the LCS cell budget and the diff collapsed into
   * "everything removed, everything added" — the view marks it instead of pretending
   * to be a minimal edit script.
   */
  collapsed: boolean
}

/** dp table cell cap: 1,000,000 cells ≈ 1000×1000 lines ≈ 4 MB Int32Array. */
const MAX_LCS_CELLS = 1_000_000

export function diffLines(before: string, after: string): LinesDiff {
  if (before === after) {
    return { lines: sameLines(before), addedCount: 0, removedCount: 0, collapsed: false }
  }
  const left = splitLines(before)
  const right = splitLines(after)
  if (left.length * right.length > MAX_LCS_CELLS) {
    return {
      lines: [
        ...left.map(text => ({ type: 'removed' as const, text })),
        ...right.map(text => ({ type: 'added' as const, text })),
      ],
      addedCount: right.length,
      removedCount: left.length,
      collapsed: true,
    }
  }
  const width = right.length + 1
  const table = new Int32Array((left.length + 1) * width)
  for (let i = left.length - 1; i >= 0; i -= 1) {
    for (let j = right.length - 1; j >= 0; j -= 1) {
      table[i * width + j] = left[i] === right[j]
        ? table[(i + 1) * width + j + 1] + 1
        : Math.max(table[(i + 1) * width + j], table[i * width + j + 1])
    }
  }
  const lines: DiffLine[] = []
  let addedCount = 0
  let removedCount = 0
  let i = 0
  let j = 0
  while (i < left.length && j < right.length) {
    if (left[i] === right[j]) {
      lines.push({ type: 'same', text: left[i]! })
      i += 1
      j += 1
    } else if (table[(i + 1) * width + j] >= table[i * width + j + 1]) {
      lines.push({ type: 'removed', text: left[i]! })
      i += 1
      removedCount += 1
    } else {
      lines.push({ type: 'added', text: right[j]! })
      j += 1
      addedCount += 1
    }
  }
  while (i < left.length) {
    lines.push({ type: 'removed', text: left[i]! })
    i += 1
    removedCount += 1
  }
  while (j < right.length) {
    lines.push({ type: 'added', text: right[j]! })
    j += 1
    addedCount += 1
  }
  return { lines, addedCount, removedCount, collapsed: false }
}

function sameLines(content: string): DiffLine[] {
  return splitLines(content).map(text => ({ type: 'same' as const, text }))
}

function splitLines(content: string): string[] {
  // Keep the trailing empty line of a "\n"-terminated document as a real line so a pure
  // newline change stays visible in the editor diff.
  return content.split('\n')
}

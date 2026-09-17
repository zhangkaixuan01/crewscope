import { computed, ref, type Ref } from 'vue'

/**
 * Moving one row between columns, by pointer or by keyboard.
 *
 * The keyboard path is not an accessibility extra bolted onto a mouse feature: it is the same state
 * machine driven by a different event source. Space picks the row up, the arrows walk the columns,
 * Enter drops it, and the same live region narrates both the pointer and the keyboard path — which
 * is why a drag is testable without a mouse and why the announcement and the drop can never disagree.
 *
 * It lives here rather than in a page because two boards need it: the WorkItem board and the home
 * page's personal board. The second copy would be the one that forgets to refuse a drop while
 * another command is in flight.
 */
export interface BoardDragOptions<Row, Column extends string> {
  /** The columns in visual order, which is the order the arrow keys walk. */
  columns: () => readonly Column[]
  /** What a column is called, for the narration. */
  columnLabel: (column: Column) => string
  /** The column a row is in now, so dropping it back where it came from is a no-op. */
  columnOf: (row: Row) => Column
  /** Whether the domain even has that edge. The server still gets the last word. */
  allowEdge: (row: Row, column: Column) => boolean
  /** Resolves the row the DOM event was anchored to. */
  findRow: (id: string) => Row | null
  /** Stable identity, so "the picked-up row" is never inferred from which column it sits in. */
  keyOf: (row: Row) => string
  /** What the narration calls the row. */
  nameOf: (row: Row) => string
  /** Commits the move. Rejections are the caller's to report; this keeps the narration honest. */
  drop: (row: Row, column: Column) => Promise<void>
  /** Blocks pickup while the surface cannot accept a command. */
  locked?: () => boolean
}

/**
 * `Column` is a second parameter rather than a widened `string` because the column is the transition
 * target: narrowing it here is what stops a board from being wired to a status its domain has no
 * edge to, and it keeps `overColumn` assignable to the column type the cards are rendered from.
 */
export function useBoardDrag<Row, Column extends string>(options: BoardDragOptions<Row, Column>) {
  const dragged = ref<Row | null>(null) as Ref<Row | null>
  const overColumn = ref<Column | null>(null) as Ref<Column | null>
  const announcement = ref('')

  const bounds = computed(() => options.columns())

  function allowDrop(column: Column): boolean {
    const row = dragged.value
    return Boolean(row && !options.locked?.() && options.columnOf(row) !== column && options.allowEdge(row, column))
  }

  function start(row: Row): void {
    if (options.locked?.()) return
    dragged.value = row
  }

  function end(): void {
    dragged.value = null
    overColumn.value = null
  }

  /** Highlights a column only when it would accept the row, so the highlight never lies. */
  function over(column: Column): void {
    overColumn.value = allowDrop(column) ? column : null
  }

  function leave(): void {
    overColumn.value = null
  }

  /**
   * Handles the board's key events.
   *
   * The anchor is the element the event came from, resolved through `findRow`, so the same handler
   * serves a card whose open affordance is a button and a row whose whole surface is clickable.
   */
  function onKeydown(event: KeyboardEvent, id: string | undefined): boolean {
    const row = id ? options.findRow(id) : null
    if (!row || ![' ', 'Enter', 'ArrowLeft', 'ArrowRight'].includes(event.key)) return false
    const name = options.nameOf(row)
    if (event.key === ' ') {
      if (dragged.value && options.keyOf(dragged.value) === options.keyOf(row)) {
        event.preventDefault()
        end()
        announcement.value = `${name} 已放下，状态保持为${options.columnLabel(options.columnOf(row))}`
        return true
      }
      start(row)
      // A locked board refuses the pickup, and it must refuse the narration too: announcing "picked
      // up" for a row that never left the column is the same lie as highlighting a column that
      // would reject the drop.
      if (!dragged.value) return false
      event.preventDefault()
      announcement.value = `已拾起 ${name}，使用左右方向键选择列，Enter 放下`
      return true
    }
    if (!dragged.value || options.keyOf(dragged.value) !== options.keyOf(row)) return false
    event.preventDefault()
    if (event.key === 'Enter') {
      announcement.value = `${name} 正在更新状态`
      // Both ends of a drag go through `pointerDrop`, so the keyboard path inherits the refusal
      // check, the failure narration and the release — the two paths cannot drift apart.
      void pointerDrop(overColumn.value ?? options.columnOf(row))
      return true
    }
    const columns = bounds.value
    const direction = event.key === 'ArrowRight' ? 1 : -1
    let index = columns.indexOf(overColumn.value ?? options.columnOf(row)) + direction
    // Walk past the columns that would refuse the row rather than lighting one up and narrating it.
    // Naming a column the drop then rejects is the worst of both: the member hears a destination,
    // presses Enter, and nothing moves.
    while (index >= 0 && index < columns.length && !allowDrop(columns[index]!)) index += direction
    const next = columns[index]
    if (next) {
      over(next)
      announcement.value = `目标列：${options.columnLabel(next)}`
    } else {
      announcement.value = `${name} 往这个方向没有可移动到的状态`
    }
    return true
  }

  /**
   * The drop, for either input. Reuses `allowDrop`, so a drop onto a refused column narrates nothing
   * and simply releases the row.
   */
  async function pointerDrop(column: Column): Promise<void> {
    const row = dragged.value
    if (!row || !allowDrop(column)) {
      end()
      return
    }
    try {
      await options.drop(row, column)
    } catch {
      // The caller's Store holds the sanitized wording; the narration must not claim success.
      announcement.value = `${options.nameOf(row)} 未能更新，请查看提示后重试`
    } finally {
      end()
    }
  }

  return { dragged, overColumn, announcement, allowDrop, start, end, over, leave, onKeydown, pointerDrop }
}

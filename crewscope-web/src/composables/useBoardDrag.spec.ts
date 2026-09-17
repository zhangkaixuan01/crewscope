import { ref } from 'vue'
import { useBoardDrag } from './useBoardDrag'

interface Row { id: string; name: string; column: string }

const columns = ['BACKLOG', 'READY', 'DONE'] as const
const labels: Record<string, string> = { BACKLOG: '待办', READY: '就绪', DONE: '完成' }

function setup(options: { rows?: Row[]; locked?: boolean } = {}) {
  const rows = ref<Row[]>(options.rows ?? [
    { id: 'a', name: 'CRW-1', column: 'BACKLOG' },
    { id: 'b', name: 'CRW-2', column: 'BACKLOG' },
  ])
  const dropped: Array<{ id: string; column: string }> = []
  const drag = useBoardDrag<Row, (typeof columns)[number]>({
    columns: () => columns,
    columnLabel: column => labels[column]!,
    columnOf: row => row.column as (typeof columns)[number],
    // READY is the only column a row may move to; DONE stands in for an edge the domain refuses.
    allowEdge: (_row, column) => column === 'READY',
    findRow: id => rows.value.find(row => row.id === id) ?? null,
    keyOf: row => row.id,
    nameOf: row => row.name,
    drop: async (row, column) => { dropped.push({ id: row.id, column }) },
    locked: options.locked ? () => true : undefined,
  })
  return { rows, drag, dropped }
}

function press(key: string): KeyboardEvent {
  return new KeyboardEvent('keydown', { key, cancelable: true })
}

/** Lets a settled drop finish: the command is awaited, then the release runs in its `finally`. */
function flush(): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, 0))
}

describe('useBoardDrag', () => {
  it('picks a row up with Space and drops it back where it was without moving it', () => {
    const { drag } = setup()

    drag.onKeydown(press(' '), 'a')
    expect(drag.dragged.value?.id).toBe('a')
    expect(drag.announcement.value).toContain('已拾起 CRW-1')

    drag.onKeydown(press(' '), 'a')
    expect(drag.dragged.value).toBeNull()
    expect(drag.announcement.value).toContain('已放下，状态保持为待办')
  })

  it('walks the columns with the arrow keys, skipping the ones that would refuse the row', async () => {
    const { drag, dropped } = setup()
    drag.onKeydown(press(' '), 'a')

    drag.onKeydown(press('ArrowRight'), 'a')
    expect(drag.overColumn.value).toBe('READY')
    expect(drag.announcement.value).toBe('目标列：就绪')

    // DONE is past READY and refuses the row, so the walk must not stop there: naming a column the
    // drop would reject leaves the member pressing Enter at a destination that does nothing.
    drag.onKeydown(press('ArrowRight'), 'a')
    expect(drag.overColumn.value).toBe('READY')
    expect(drag.announcement.value).toBe('CRW-1 往这个方向没有可移动到的状态')

    drag.onKeydown(press('Enter'), 'a')
    await flush()
    expect(dropped).toEqual([{ id: 'a', column: 'READY' }])
    expect(drag.announcement.value).toBe('CRW-1 正在更新状态')
    expect(drag.dragged.value).toBeNull()
  })

  it('ignores keys aimed at another row, even one sitting in the same column', () => {
    const { drag, dropped } = setup()
    drag.onKeydown(press(' '), 'a')

    // Two rows in one column is why identity is a key and not "the column the dragged row is in":
    // inferring from the column would let CRW-2 steer CRW-1's drop.
    expect(drag.onKeydown(press('Enter'), 'b')).toBe(false)
    expect(dropped).toEqual([])
    expect(drag.dragged.value?.id).toBe('a')
  })

  it('refuses to pick a row up while the surface is locked, and says nothing about picking it up', () => {
    const { drag } = setup({ locked: true })

    drag.start({ id: 'a', name: 'CRW-1', column: 'BACKLOG' })
    const handled = drag.onKeydown(press(' '), 'a')

    expect(drag.dragged.value).toBeNull()
    expect(drag.allowDrop('READY')).toBe(false)
    // Reporting the press as handled would swallow the page's own scrolling for a pickup that is
    // not going to happen; narrating it would be worse.
    expect(handled).toBe(false)
    expect(drag.announcement.value).toBe('')
  })

  it('drops the narration when a locked row was already picked up', () => {
    const { drag } = setup()
    drag.onKeydown(press(' '), 'a')
    expect(drag.dragged.value?.id).toBe('a')

    // Space on the same row always releases it, locked or not: a member who picked a row up and then
    // lost the network must still be able to put it back down.
    expect(drag.onKeydown(press(' '), 'a')).toBe(true)
    expect(drag.dragged.value).toBeNull()
    expect(drag.announcement.value).toBe('CRW-1 已放下，状态保持为待办')
  })

  it('highlights nothing for a column that would refuse the row', () => {
    const { drag } = setup()
    drag.start({ id: 'a', name: 'CRW-1', column: 'BACKLOG' })

    drag.over('DONE')
    expect(drag.overColumn.value).toBeNull()

    drag.over('BACKLOG')
    expect(drag.overColumn.value).toBeNull()

    drag.over('READY')
    expect(drag.overColumn.value).toBe('READY')
  })

  it('drops nothing onto a refused column and clears the drag', async () => {
    const { drag, dropped } = setup()
    drag.start({ id: 'a', name: 'CRW-1', column: 'BACKLOG' })

    await drag.pointerDrop('DONE')

    expect(dropped).toEqual([])
    expect(drag.dragged.value).toBeNull()
    expect(drag.overColumn.value).toBeNull()
  })

  it('narrates a rejected drop as a failure instead of claiming the move happened', async () => {
    const { drag } = setup()
    const failing = useBoardDrag<Row, (typeof columns)[number]>({
      columns: () => columns,
      columnLabel: column => labels[column]!,
      columnOf: row => row.column as (typeof columns)[number],
      allowEdge: () => true,
      findRow: () => ({ id: 'a', name: 'CRW-1', column: 'BACKLOG' }),
      keyOf: row => row.id,
      nameOf: row => row.name,
      drop: async () => { throw new Error('conflict') },
    })
    failing.onKeydown(press(' '), 'a')
    failing.onKeydown(press('ArrowRight'), 'a')

    failing.onKeydown(press('Enter'), 'a')
    await flush()

    expect(failing.announcement.value).toBe('CRW-1 未能更新，请查看提示后重试')
    expect(failing.dragged.value).toBeNull()
    expect(drag.announcement.value).toBe('')
  })
})

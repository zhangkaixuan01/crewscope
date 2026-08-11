import { parseServerSentEvents } from './sse'

describe('SSE parser', () => {
  it('parses CRLF, multiline data and arbitrary chunk boundaries', async () => {
    const body = stream(['id: cursor-1\r\nev', 'ent: RUN_STARTED\r\ndata: {"a":', '1}\r\ndata: tail\r\n\r\n:data heartbeat\n\ndata: {"b":2}\n\n'])

    const frames = []
    for await (const frame of parseServerSentEvents(body)) frames.push(frame)

    expect(frames).toEqual([
      { id: 'cursor-1', event: 'RUN_STARTED', data: '{"a":1}\ntail' },
      { id: null, event: null, data: '{"b":2}' },
    ])
  })

  it('emits the final data frame when a stream closes without a blank line', async () => {
    const frames = []
    for await (const frame of parseServerSentEvents(stream(['event: RUN_FINISHED\ndata: {}']))) frames.push(frame)

    expect(frames).toEqual([{ id: null, event: 'RUN_FINISHED', data: '{}' }])
  })

  it('cancels the response body when a terminal consumer stops early', async () => {
    const encoder = new TextEncoder()
    let cancelled = false
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: RUN_FINISHED\ndata: {}\n\n'))
      },
      cancel() {
        cancelled = true
      },
    })

    for await (const _frame of parseServerSentEvents(body)) break

    expect(cancelled).toBe(true)
  })
})

function stream(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  return new ReadableStream({
    start(controller) {
      chunks.forEach(chunk => controller.enqueue(encoder.encode(chunk)))
      controller.close()
    },
  })
}

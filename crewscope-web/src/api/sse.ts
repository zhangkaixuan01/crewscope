export interface ServerSentEventFrame {
  id: string | null
  event: string | null
  data: string
}

/** Parses SSE across arbitrary network chunk boundaries without interpreting payload contents. */
export async function* parseServerSentEvents(
  body: ReadableStream<Uint8Array>,
): AsyncGenerator<ServerSentEventFrame> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let completed = false
  try {
    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      let boundary = eventBoundary(buffer)
      while (boundary) {
        const block = buffer.slice(0, boundary.index)
        buffer = buffer.slice(boundary.index + boundary.length)
        const frame = parseBlock(block)
        if (frame) yield frame
        boundary = eventBoundary(buffer)
      }
      if (done) {
        completed = true
        break
      }
    }
    const frame = parseBlock(buffer)
    if (frame) yield frame
  } finally {
    // Stopping after a terminal event must also stop the underlying HTTP body, otherwise the
    // browser can keep buffering frames that the application has deliberately stopped reading.
    if (!completed) await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}

function eventBoundary(buffer: string): { index: number; length: number } | null {
  const match = /\r?\n\r?\n/.exec(buffer)
  return match ? { index: match.index, length: match[0].length } : null
}

function parseBlock(block: string): ServerSentEventFrame | null {
  let id: string | null = null
  let event: string | null = null
  const data: string[] = []
  for (const line of block.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    const rawValue = separator < 0 ? '' : line.slice(separator + 1)
    const value = rawValue.startsWith(' ') ? rawValue.slice(1) : rawValue
    if (field === 'id') id = value
    else if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }
  return data.length > 0 ? { id, event, data: data.join('\n') } : null
}

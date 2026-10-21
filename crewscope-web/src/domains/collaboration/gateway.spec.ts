// @vitest-environment jsdom
import { collaborationWebSocketUrl, createCollaborationGateway, type CollaborationGatewayHandlers, type RawCollaborationSocket } from './gateway'

function blankSocket(): RawCollaborationSocket {
  return { send: () => {}, close: () => {}, onopen: null, onmessage: null, onclose: null, onerror: null }
}

describe('CollaborationGateway', () => {
  it('derives the WebSocket URL from the page origin (https upgrades to wss)', () => {
    expect(collaborationWebSocketUrl('https:', 'app.example.com')).toBe('wss://app.example.com/api/v1/collaboration/ws')
    expect(collaborationWebSocketUrl('http:', 'localhost:5173')).toBe('ws://localhost:5173/api/v1/collaboration/ws')
  })

  it('hands the URL to the injected socket factory and forwards message payloads as text', () => {
    const urls: string[] = []
    const sockets: RawCollaborationSocket[] = [blankSocket(), blankSocket()]
    let index = 0
    const gateway = createCollaborationGateway({
      url: 'ws://proxy.test/api/v1/collaboration/ws',
      socketFactory: url => {
        urls.push(url)
        return sockets[index++]
      },
    })
    const received: string[] = []

    gateway.connect({ onMessage: text => received.push(text), onClose: () => {} })
    gateway.connect({ onMessage: () => {}, onClose: () => {} })

    expect(urls).toEqual(['ws://proxy.test/api/v1/collaboration/ws', 'ws://proxy.test/api/v1/collaboration/ws'])
    sockets[0].onmessage?.({ data: '{"type":"ping"}' })
    expect(received).toEqual(['{"type":"ping"}'])
  })

  it('normalizes an abnormal close (no code) to 1006 and passes real codes through', () => {
    const socket = blankSocket()
    const gateway = createCollaborationGateway({ socketFactory: () => socket })
    const closes: Array<[number, string]> = []
    gateway.connect({ onMessage: () => {}, onClose: (code, reason) => closes.push([code, reason]) })

    socket.onclose?.({})
    socket.onclose?.({ code: 1013, reason: 'signal budget exceeded' })

    expect(closes).toEqual([[1006, ''], [1013, 'signal budget exceeded']])
  })

  it('delegates send and close to the underlying socket with a normal-close code', () => {
    const sent: string[] = []
    const closed: Array<[number | undefined, string | undefined]> = []
    const socket: RawCollaborationSocket = {
      send: text => sent.push(text),
      close: (code, reason) => closed.push([code, reason]),
      onopen: null,
      onmessage: null,
      onclose: null,
      onerror: null,
    }
    const handlers: CollaborationGatewayHandlers = { onMessage: () => {}, onClose: () => {} }
    const connection = createCollaborationGateway({ socketFactory: () => socket }).connect(handlers)

    connection.send('{"type":"pong"}')
    connection.close()

    expect(sent).toEqual(['{"type":"pong"}'])
    expect(closed).toEqual([[1000, 'client shutdown']])
  })
})
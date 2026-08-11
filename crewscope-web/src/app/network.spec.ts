import { createNetworkStatusController, type NetworkEnvironment } from './network'

describe('network status controller', () => {
  it('tracks browser online and offline hints with one removable listener pair', () => {
    const environment = new FakeNetworkEnvironment()
    const controller = createNetworkStatusController(environment)
    controller.start()

    environment.setOnline(false)
    expect(controller.online.value).toBe(false)
    environment.setOnline(true)
    expect(controller.online.value).toBe(true)

    controller.stop()
    environment.setOnline(false)
    expect(controller.online.value).toBe(true)
  })
})

class FakeNetworkEnvironment implements NetworkEnvironment {
  navigator = { onLine: true }
  private readonly listeners = new Map<string, Set<() => void>>()

  addEventListener(type: 'online' | 'offline', listener: () => void): void {
    const values = this.listeners.get(type) ?? new Set()
    values.add(listener)
    this.listeners.set(type, values)
  }

  removeEventListener(type: 'online' | 'offline', listener: () => void): void {
    this.listeners.get(type)?.delete(listener)
  }

  setOnline(value: boolean): void {
    this.navigator.onLine = value
    for (const listener of this.listeners.get(value ? 'online' : 'offline') ?? []) listener()
  }
}

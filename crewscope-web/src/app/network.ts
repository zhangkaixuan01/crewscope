import { onMounted, onUnmounted, readonly, ref, type DeepReadonly, type Ref } from 'vue'

export interface NetworkEnvironment {
  navigator: { onLine: boolean }
  addEventListener(type: 'online' | 'offline', listener: () => void): void
  removeEventListener(type: 'online' | 'offline', listener: () => void): void
}

export interface NetworkStatusController {
  online: DeepReadonly<Ref<boolean>>
  start(): void
  stop(): void
}

/** Browser network hints improve interaction only; server requests remain the source of truth. */
export function createNetworkStatusController(environment: NetworkEnvironment): NetworkStatusController {
  const online = ref(environment.navigator.onLine)
  let started = false
  const synchronize = () => { online.value = environment.navigator.onLine }

  function start(): void {
    if (started) return
    started = true
    synchronize()
    environment.addEventListener('online', synchronize)
    environment.addEventListener('offline', synchronize)
  }

  function stop(): void {
    if (!started) return
    started = false
    environment.removeEventListener('online', synchronize)
    environment.removeEventListener('offline', synchronize)
  }

  return { online: readonly(online), start, stop }
}

const browserController = typeof window === 'undefined'
  ? null
  : createNetworkStatusController(window)
let browserConsumers = 0

/** Shares one browser listener across AppShell and the active feature page. */
export function useNetworkStatus(): DeepReadonly<Ref<boolean>> {
  const fallback = readonly(ref(true))
  onMounted(() => {
    browserConsumers += 1
    if (browserConsumers === 1) browserController?.start()
  })
  onUnmounted(() => {
    browserConsumers = Math.max(0, browserConsumers - 1)
    if (browserConsumers === 0) browserController?.stop()
  })
  return browserController?.online ?? fallback
}

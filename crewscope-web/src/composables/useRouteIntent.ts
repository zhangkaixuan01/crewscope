import { onScopeDispose, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * Consumes a one-shot `intent=` parameter after the caller authorizes its concrete scope,
 * then strips it from the URL so refreshes and shared links never repeat the action. Mirrors
 * {@link useCreationEntry}; the existing `create=1` entry keeps its own contract untouched.
 */
export function useRouteIntent<K extends string>(
  routeName: string,
  knownIntents: readonly K[],
  ready: () => boolean,
  handle: (intent: K) => void,
): void {
  const route = useRoute()
  const router = useRouter()
  let alive = true
  onScopeDispose(() => { alive = false })
  watch(() => [route.query.intent, ready()] as const, async ([value]) => {
    if (route.name !== routeName) return
    if (typeof value !== 'string' || !(knownIntents as readonly string[]).includes(value)) return
    if (!ready()) return
    const query = { ...route.query }
    delete query.intent
    const failure = await router.replace({ query })
    if (!failure && alive && route.name === routeName && ready()) handle(value as K)
  }, { immediate: true })
}

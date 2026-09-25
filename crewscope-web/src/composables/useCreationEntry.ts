import { onScopeDispose, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/** Consume a navigation-only intent once after the caller has authorized its concrete scope. */
export function useCreationEntry(routeName: string, readyScope: () => string | null, open: () => void) {
  const route = useRoute()
  const router = useRouter()
  let alive = true
  onScopeDispose(() => { alive = false })
  watch(() => [route.query.create, readyScope()], async () => {
    const scope = readyScope()
    if (route.query.create !== '1' || !scope || route.name !== routeName) return
    const query = routeName === 'work'
      ? { team: route.query.team, project: route.query.project }
      : { team: route.query.team }
    const failure = await router.replace({ query })
    if (!failure && alive && route.name === routeName && readyScope() === scope) open()
  }, { immediate: true })
}

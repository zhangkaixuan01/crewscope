import { inject, reactive, readonly, type App, type Component, type InjectionKey } from 'vue'
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router'
import type { AuthenticatedPrincipal } from './auth'

/** Context passed to every front-end action. It is deliberately separate from backend Action*. */
export interface ActionContext {
  router: Router
  route: RouteLocationNormalizedLoaded
  principal: AuthenticatedPrincipal | null
}

export type ActionGroup = '导航' | '创建' | '视图' | '执行控制' | '对象'

export interface ActionDefinition {
  id: string
  label: string
  description?: string
  group: ActionGroup
  keywords?: readonly string[]
  shortcut?: string
  icon?: Component
  requiredPermission?: string
  /** Additional runtime visibility check. Returning false hides the action completely. */
  visible?: (context: ActionContext) => boolean
  /** Availability is presentation-only; handlers must still enforce server authorization. */
  enabled?: (context: ActionContext) => boolean
  execute: (context: ActionContext) => void | Promise<void>
}

export interface ActionEntry {
  action: ActionDefinition
  available: boolean
  unavailableReason?: string
}

interface ActionRegistryState { revision: number }

export class ActionRegistry {
  private readonly actions = new Map<string, ActionDefinition>()
  private readonly state = reactive<ActionRegistryState>({ revision: 0 })

  /** Register an action once and return an idempotent cleanup function for tests/plugins. */
  register(action: ActionDefinition): () => void {
    if (!action.id.trim()) throw new TypeError('Action id must not be empty')
    if (this.actions.has(action.id)) throw new Error(`Action already registered: ${action.id}`)
    this.actions.set(action.id, action)
    this.state.revision += 1
    return () => this.unregister(action.id)
  }

  unregister(id: string): boolean {
    const removed = this.actions.delete(id)
    if (removed) this.state.revision += 1
    return removed
  }

  get(id: string): ActionDefinition | undefined { return this.actions.get(id) }

  all(): readonly ActionDefinition[] {
    // Touch the reactive revision so computed consumers update after plugin registration.
    void this.state.revision
    return [...this.actions.values()]
  }

  list(context: ActionContext, includeUnavailable = false): ActionEntry[] {
    return this.all()
      .filter(action => action.visible?.(context) !== false)
      .map(action => {
        const permissionAllowed = !action.requiredPermission || Boolean(context.principal?.permissions.has(action.requiredPermission))
        const enabled = permissionAllowed && (action.enabled?.(context) ?? true)
        return {
          action,
          available: enabled,
          unavailableReason: !permissionAllowed ? '当前身份没有执行此操作的权限。' : enabled ? undefined : '当前上下文暂时不能执行此操作。',
        }
      })
      .filter(entry => includeUnavailable || entry.available)
  }

  conflicts(): Map<string, string[]> {
    const grouped = new Map<string, string[]>()
    for (const action of this.all()) {
      if (!action.shortcut) continue
      const ids = grouped.get(normalizeShortcut(action.shortcut)) ?? []
      ids.push(action.id)
      grouped.set(normalizeShortcut(action.shortcut), ids)
    }
    return new Map([...grouped].filter(([, ids]) => ids.length > 1))
  }

  async execute(id: string, context: ActionContext): Promise<boolean> {
    const action = this.get(id)
    if (!action || this.list(context).every(entry => entry.action.id !== id)) return false
    await action.execute(context)
    return true
  }
}

export const ACTION_REGISTRY: InjectionKey<ActionRegistry> = Symbol('crewscope-action-registry')

export function createActionRegistry(): ActionRegistry { return new ActionRegistry() }

export function installActionRegistry(app: App, registry: ActionRegistry): void { app.provide(ACTION_REGISTRY, registry) }

export function useActionRegistry(): ActionRegistry {
  const registry = inject(ACTION_REGISTRY)
  if (!registry) throw new Error('CrewScope Action Registry is not installed')
  return registry
}

function normalizeShortcut(shortcut: string): string {
  return shortcut.trim().toLowerCase().replace(/\s+/g, ' ')
}

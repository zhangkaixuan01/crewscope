import { canonicalCommandInput } from './commandIntent'
import { secureId } from './secureId'

/** Component-owned only. Clear on close/unmount/identity change; never install in a global Store. */
export function createFormCommandKeys() {
  const keys = new Map<string, string>()
  return {
    forInput(input: unknown): string {
      const snapshot = canonicalCommandInput(input)
      const existing = keys.get(snapshot)
      if (existing) return existing
      if (keys.size >= 100) throw new Error('请先核实已有提交结果，再重新打开表单。')
      const key = secureId()
      keys.set(snapshot, key)
      return key
    },
    clear: () => keys.clear(),
  }
}

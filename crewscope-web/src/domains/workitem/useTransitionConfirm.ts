import { computed, ref, type Ref } from 'vue'
import type { WorkItemAvailableTransition, WorkItemStatus } from './types'

/**
 * Confirms the irreversible actions and commits the rest.
 *
 * An action that cannot be reversed gets its confirmation *before* the command, because there is no
 * reverse edge to offer afterwards. Everything the server marks reversible skips the second click
 * precisely because the Store opens an undo window instead — so this never re-derives reversibility
 * from the state machine, it reads the server's answer.
 *
 * It is a composable rather than a page function because four surfaces execute the same actions
 * (drawer, list row, board card, home page). The second copy of this rule would be the one that
 * confirms the reversible action and runs the irreversible one without asking.
 *
 * The subject is part of the state: on a page of five hundred rows exactly one card may be waiting
 * for its second click, and a confirmation that did not record *which* item asked for it would arm
 * every card carrying that status.
 */
export function useTransitionConfirm(busy: () => boolean) {
  const pending = ref<{ subjectId: string; targetStatus: WorkItemStatus } | null>(null)

  /** The action awaiting a second click, for the surface that asked for it. */
  const confirmingTarget: Ref<WorkItemStatus | null> = computed(() => pending.value?.targetStatus ?? null)

  /** Which item is waiting, so the other rows on the page can render as ordinary rows. */
  const confirmingSubject: Ref<string | null> = computed(() => pending.value?.subjectId ?? null)

  /**
   * Runs the action, or arms its confirmation on the first press.
   *
   * The confirmation is keyed to the target status, not just the item: pressing a different
   * irreversible action re-arms instead of executing, so a member who changes their mind does not
   * commit the first action with the second click.
   */
  async function submit(
    subjectId: string,
    transition: WorkItemAvailableTransition,
    run: (transition: WorkItemAvailableTransition) => Promise<void>,
  ): Promise<void> {
    if (!transition.enabled || busy()) return
    if (!transition.reversible && !(pending.value?.subjectId === subjectId && pending.value.targetStatus === transition.targetStatus)) {
      pending.value = { subjectId, targetStatus: transition.targetStatus }
      return
    }
    pending.value = null
    await run(transition)
  }

  /** Drops a pending confirmation, for when the surface that asked for it has gone away. */
  function reset(): void {
    pending.value = null
  }

  return { confirmingTarget, confirmingSubject, submit, reset }
}

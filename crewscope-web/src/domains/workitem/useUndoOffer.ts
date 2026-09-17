import { useToast } from '../../composables/useToast'
import type { WorkItemStore } from './store'

/**
 * Surfaces the Store's undo offer, whatever opened it.
 *
 * The board drop, a card action, the drawer and the home page all funnel through here, so there is
 * exactly one undo affordance with one window. The Store decides *whether* an offer exists — it only
 * opens one for an edge the server marked reversible and whose reverse edge the domain can actually
 * execute — which is why this never re-derives reversibility from the state machine.
 *
 * It lives beside the Store rather than in one page because the home page executes the same actions
 * the board does: two copies of the window would be two windows, and the second one would be the
 * copy that forgets to expire.
 */
export function useUndoOffer(store: WorkItemStore) {
  const toast = useToast()

  function offerUndo(subject?: string): void {
    const offer = store.state.undoOffer
    if (!offer) return
    const remaining = offer.expiresAt - Date.now()
    if (remaining <= 0) {
      store.dismissUndoOffer()
      return
    }
    toast.show(`${subject ? `${subject} ` : ''}已${offer.actionLabel}`, {
      tone: 'success',
      duration: remaining,
      action: { label: '撤销', onClick: undoTransition },
    })
  }

  async function undoTransition(): Promise<void> {
    try {
      await store.undoTransition()
    } catch {
      // The Store exposes the sanitized conflict; the reverse edge is a normal command like any other.
    }
  }

  return { offerUndo, undoTransition }
}

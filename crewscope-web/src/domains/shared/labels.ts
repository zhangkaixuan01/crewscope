/**
 * The single entry point for turning a server-owned enum value into user-facing text.
 *
 * Per-domain `labels.ts` files own the maps and must type them as `Record<Enum, string>` so a new
 * backend enum value fails typechecking instead of leaking the raw constant onto the screen. This
 * helper only handles the null/unknown edges; it deliberately does not own any wording.
 */
export function enumLabel(value: string | null | undefined, labels: Record<string, string>): string {
  return value ? labels[value] ?? value : '—'
}

/** Same contract as {@link enumLabel} but renders a caller-supplied placeholder for absent values. */
export function enumLabelOr(
  value: string | null | undefined,
  labels: Record<string, string>,
  fallback: string,
): string {
  return value ? labels[value] ?? value : fallback
}

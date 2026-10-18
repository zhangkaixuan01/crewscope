/** Shared visual tone vocabulary for status-oriented base and domain components. */
export type SemanticTone = 'neutral' | 'info' | 'agent' | 'warning' | 'danger' | 'success'

/**
 * Shared prop slices for base/shared components (M12b-F04). Slices compose with each
 * component's own props via intersection so the runtime contract stays flat.
 */
/** The control is inert until the caller re-enables it (reason handled by the caller). */
export interface DisabledProps { disabled?: boolean }
/** The control shows its in-flight state instead of accepting interaction. */
export interface LoadingProps { loading?: boolean }
/** Network hint: commands stay closed while offline, read surfaces may keep their cache. */
export interface OnlineProps { online: boolean }
/** The last load attempt failed (status code stays component-specific when it matters). */
export interface ErrorMessageProps { errorMessage: string | null }

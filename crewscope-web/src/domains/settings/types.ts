/** Team-selected settings boundary shared by Model and Agent browser stores. */
export interface SettingsScope {
  organizationId: string
  teamId: string
}

/** Bounded continuation used by A01-A03, whose collection contract is offset based. */
export interface OffsetPage<T> {
  items: T[]
  nextOffset: number | null
}

/** Public aggregate together with the exact strong ETag returned by the server. */
export interface Etagged<T> {
  value: T
  etag: string
}

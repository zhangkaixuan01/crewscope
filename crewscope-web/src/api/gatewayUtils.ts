/**
 * Gateway HTTP-edge utilities shared by every domain gateway (M12b-F05): one definition
 * each instead of twenty-one `segment`, eight `pick` and five `etagVersion` local copies.
 */
/** URI path-segment encoding (IDs with `/`, `?` or non-ASCII must not break the path). */
export function segment(value: string): string {
  return encodeURIComponent(value)
}

/** DTO allow-list projection: keep only the listed keys of a parsed response object. */
export function pick<T extends object, K extends keyof T>(value: T, keys: readonly K[]): Pick<T, K> {
  return Object.fromEntries(keys.map(key => [key, value[key]])) as Pick<T, K>
}

/** Strong-ETag version parse; `label` names the surface in the thrown message. */
export function etagVersion(etag: string, label: string): number {
  if (!/^"\d+"$/.test(etag)) throw new TypeError(`${label} ETag is invalid`)
  const version = Number(etag.slice(1, -1))
  if (!Number.isSafeInteger(version)) throw new TypeError(`${label} ETag is invalid`)
  return version
}

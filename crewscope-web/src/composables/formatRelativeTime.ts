const minute = 60 * 1000
const hour = 60 * minute
const day = 24 * hour
const week = 7 * day

/** Stable Chinese relative-time presentation used by all list surfaces. */
export function formatRelativeTime(input: string | number | Date, now = Date.now()): string {
  const timestamp = input instanceof Date ? input.getTime() : typeof input === 'number' ? input : new Date(input).getTime()
  if (!Number.isFinite(timestamp)) return '时间未知'
  const delta = now - timestamp
  if (delta < 0) return '刚刚'
  if (delta < minute) return '刚刚'
  if (delta < hour) return `${Math.floor(delta / minute)} 分钟前`
  if (delta < day) return `${Math.floor(delta / hour)} 小时前`
  if (delta < week) {
    const date = new Date(timestamp)
    return new Intl.DateTimeFormat('zh-CN', { weekday: 'short', hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
  }
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(timestamp))
}

/** Full absolute time belongs in the tooltip/title, never in the primary list label. */
export function formatAbsoluteTime(input: string | number | Date): string {
  const timestamp = input instanceof Date ? input : new Date(input)
  if (!Number.isFinite(timestamp.getTime())) return '时间未知'
  // Explicit fields keep the formatter compatible with Safari and older ICU
  // builds, which reject `timeZoneName` alongside dateStyle/timeStyle.
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    timeZoneName: 'short',
  }).format(timestamp)
}

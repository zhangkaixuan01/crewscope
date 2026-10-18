/**
 * Shared zh-CN timestamp formatting for detail surfaces (M12b-F04): medium date, short time.
 * Callers with their own null/invalid handling (dash fallbacks) keep local wrappers.
 */
export function displayDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

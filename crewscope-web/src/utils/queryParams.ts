/**
 * URL query 参数读取工具（M12b-F03 收敛）。
 *
 * 此前 7 个页面各抄一份且已漂移：同一批函数有 `''` 与 `null` 两种空值流派，
 * Audit/Lark 的 `''` 流派在消费点又被 `|| null` 翻转回来。这里统一为：
 * 空/无效值一律 `null`；表单层的 `''` 哨兵由页面用 `?? ''` 自行落回。
 */

/** query 值 → 非空字符串；空串、数组、缺省都算「没有」。 */
export function queryValue(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

/** 白名单命中则原样返回，否则 fallback。 */
export function oneOf<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  const parsed = queryValue(value)
  return parsed && allowed.includes(parsed as T) ? parsed as T : fallback
}

/** 白名单命中则原样返回，否则 `null`——没有天然默认值的筛选用这个。 */
export function enumQuery<T extends string>(value: unknown, choices: readonly T[]): T | null {
  const parsed = queryValue(value)
  return parsed && choices.includes(parsed as T) ? parsed as T : null
}

/** 严格 UUID（版本位 1–5）或 `null`——深链里的 id 不允许形似即过。 */
export function uuidQuery(value: unknown): string | null {
  const parsed = queryValue(value)
  return parsed && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(parsed) ? parsed : null
}

/** ≥1 的整数 revision 或 `null`。 */
export function revisionQuery(value: unknown): number | null {
  const parsed = queryValue(value)
  return parsed && /^\d+$/.test(parsed) && Number(parsed) >= 1 ? Number(parsed) : null
}

/** 本地分钟精度时间（`yyyy-MM-ddTHH:mm`）且真实可解析，或 `null`——Audit 的时间筛选合同。 */
export function localDateQuery(value: unknown): string | null {
  const parsed = queryValue(value)
  return parsed && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(parsed) && Number.isFinite(new Date(parsed).getTime()) ? parsed : null
}

/** 把「非默认值」写回 query 对象（默认值 = 从 URL 删除）；返回是否真的变了。 */
export function normalizeQuery(query: Record<string, unknown>, key: string, value: string, defaultValue: string): boolean {
  const current = queryValue(query[key])
  const expected = value === defaultValue ? null : value
  if (current === expected) return false
  if (expected) query[key] = expected
  else delete query[key]
  return true
}

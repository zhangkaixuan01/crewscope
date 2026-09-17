import {
  agentGenerateOptionLimits,
  type AgentGenerateOptionField,
  type AgentGenerateOptionLimit,
} from '../../api/generated/agent-limits'

export type { AgentGenerateOptionField, AgentGenerateOptionLimit }

/**
 * The one reader of the generated GenerateOptions ranges.
 *
 * The table is generated from the domain's `AgentGenerateOptionsLimits`, so the sentence the form
 * prints, the `min`/`max`/`step` its inputs carry and the values the server accepts are three views of
 * one record. Nothing here re-states a bound: adding a field to the domain table is enough for the
 * form to render it correctly, and changing a bound breaks the drift gate rather than the member.
 */

export function generateOptionLimit(field: AgentGenerateOptionField): AgentGenerateOptionLimit {
  return agentGenerateOptionLimits[field]
}

/** The field names in declaration order, which is the order the form renders them. */
export function generateOptionFields(): AgentGenerateOptionField[] {
  return Object.keys(agentGenerateOptionLimits) as AgentGenerateOptionField[]
}

/** A bound as a number for the input's own attributes. */
export function limitBound(field: AgentGenerateOptionField, bound: 'minimum' | 'maximum' | 'step'): number {
  return Number(generateOptionLimit(field)[bound])
}

/** Whether the field takes a whole number or a decimal; decides the keypad and the validation. */
export function limitShape(field: AgentGenerateOptionField): 'DECIMAL' | 'INTEGER' {
  return generateOptionLimit(field).shape
}

/**
 * The smallest value the field accepts, for the input's own `min`.
 *
 * An open floor moves up by one step — topP's table minimum is 0 and 0 is not a value it takes — so
 * the browser's own constraint and the visible error refuse the same numbers. The upper bound needs no
 * such adjustment: it is inclusive for every field the domain publishes.
 */
export function limitFloor(field: AgentGenerateOptionField): number {
  const limit = generateOptionLimit(field)
  const minimum = Number(limit.minimum)
  return limit.includeMinimum ? minimum : minimum + Number(limit.step)
}

/**
 * A bound as the form prints it.
 *
 * A whole-number field is grouped the way the surrounding copy is ("10,000,000"); a decimal field
 * keeps the published spelling, because the text is what a member re-types.
 */
function formatBound(limit: AgentGenerateOptionLimit, bound: 'minimum' | 'maximum'): string {
  return limit.shape === 'INTEGER'
    ? new Intl.NumberFormat('en-US').format(Number(limit[bound]))
    : limit[bound]
}

/** The range a field accepts, as one phrase: "0–2", or "大于 0 且不超过 1" when the floor is open. */
export function limitRangeText(field: AgentGenerateOptionField): string {
  const limit = generateOptionLimit(field)
  const minimum = formatBound(limit, 'minimum')
  const maximum = formatBound(limit, 'maximum')
  return limit.includeMinimum ? `${minimum}–${maximum}` : `大于 ${minimum} 且不超过 ${maximum}`
}

/** The visible field-level message for a value outside the range, naming the range it must be in. */
export function limitErrorMessage(field: AgentGenerateOptionField): string {
  return generateOptionLimit(field).includeMinimum
    ? `请输入 ${limitRangeText(field)} 之间的值。`
    : `请输入${limitRangeText(field)}的值。`
}

/**
 * A field's raw value: text while it is untouched, the number the browser parsed once a member types.
 * Both arrive here, and an empty field arrives as an empty string.
 */
export type PreferenceValue = string | number | null | undefined

/**
 * Whether a typed value is inside the field's range.
 *
 * An empty value is the model default for every optional field — but not for a required one, whose
 * parameter is a primitive and would otherwise be read as zero and sent as one. A whole-number field
 * also refuses a fraction, which the server's `long`/`int` cannot carry.
 */
export function withinGenerateOptionLimit(field: AgentGenerateOptionField, value: PreferenceValue): boolean {
  const text = asText(value)
  if (text === null) return !generateOptionLimit(field).required
  const limit = generateOptionLimit(field)
  const parsed = Number(text)
  if (!Number.isFinite(parsed)) return false
  if (limit.shape === 'INTEGER' && !Number.isSafeInteger(parsed)) return false
  const aboveMinimum = limit.includeMinimum
    ? parsed >= Number(limit.minimum)
    : parsed > Number(limit.minimum)
  return aboveMinimum && parsed <= Number(limit.maximum)
}

/** The trimmed text of a field value, or null when the field is empty. */
function asText(value: PreferenceValue): string | null {
  if (value === null || value === undefined) return null
  const text = String(value).trim()
  return text === '' ? null : text
}

/**
 * Seed is not in the published table on purpose: the server takes it as an optional whole number with
 * no range, so the only honest bound is what a JSON number can carry without losing digits. Kept here
 * beside the ranged fields so the form validates all of its numeric inputs through one module.
 */
export const seedBound = { minimum: Number.MIN_SAFE_INTEGER, maximum: Number.MAX_SAFE_INTEGER } as const

export const seedErrorMessage = '请输入安全整数范围内的值。'

export function withinSeedBound(value: PreferenceValue): boolean {
  const text = asText(value)
  if (text === null) return true
  const parsed = Number(text)
  return Number.isSafeInteger(parsed) && parsed >= seedBound.minimum && parsed <= seedBound.maximum
}

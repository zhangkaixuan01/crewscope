/* eslint-disable */
// GENERATED FILE. Source: crewscope-domain AgentGenerateOptionsLimits.
// Regenerate with: node scripts/generate-agent-limits.mjs
// Domain source SHA-256: 0b6565035dd080ee89a9ea61816cd946cce538c3d55f531ee2d79365d4def2e6

/**
 * One configurable GenerateOptions field, exactly as the domain publishes it.
 *
 * The bounds are strings because their spelling is part of the contract: the temperature step is
 * "0.01" and not 0.010 or 1E-2. Parse them with `Number()` and format them from the record, so a
 * label and the input's own attributes can never disagree.
 */
export interface AgentGenerateOptionLimit {
  readonly field: string
  readonly shape: 'DECIMAL' | 'INTEGER'
  readonly minimum: string
  readonly maximum: string
  /** Whether `minimum` itself is inside the range. */
  readonly includeMinimum: boolean
  readonly step: string
  /** Whether the field must carry a number: an empty value is not the model default for this one. */
  readonly required: boolean
}

/** Declaration order is the order the form renders, and `Object.keys` preserves it. */
export const agentGenerateOptionLimits = {
  "temperature": {
    "field": "temperature",
    "shape": "DECIMAL",
    "minimum": "0",
    "maximum": "2",
    "includeMinimum": true,
    "step": "0.01",
    "required": false
  },
  "topP": {
    "field": "topP",
    "shape": "DECIMAL",
    "minimum": "0",
    "maximum": "1",
    "includeMinimum": false,
    "step": "0.01",
    "required": false
  },
  "maximumOutputTokens": {
    "field": "maximumOutputTokens",
    "shape": "INTEGER",
    "minimum": "1",
    "maximum": "10000000",
    "includeMinimum": true,
    "step": "1",
    "required": false
  },
  "maximumAttempts": {
    "field": "maximumAttempts",
    "shape": "INTEGER",
    "minimum": "1",
    "maximum": "10",
    "includeMinimum": true,
    "step": "1",
    "required": true
  }
} as const satisfies Readonly<Record<string, AgentGenerateOptionLimit>>

export type AgentGenerateOptionField = keyof typeof agentGenerateOptionLimits

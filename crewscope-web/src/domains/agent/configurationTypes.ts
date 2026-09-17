import type { AgentExecutionScope } from './types'

export interface BindingForm {
  kind: 'DIRECT' | 'INHERIT_TEAM_DEFAULT'
  primary: string
  fallback: string
}

export type AgentBindingForms = Record<AgentExecutionScope, BindingForm>

/**
 * A numeric preference as the form actually holds it.
 *
 * `<input type="number">` with `v-model` assigns the *number* it parsed, and an emptied field stays
 * text — so a field declared as `string` would hold a number the moment a member types in it, and
 * every reader that assumed text (`.trim()`) would throw on save. The union says what happens instead.
 */
export type PreferenceNumber = string | number

export interface PreferenceForm {
  supplementalInstructions: string
  approvedSkillKeys: string[]
  temperature: PreferenceNumber
  topP: PreferenceNumber
  maximumOutputTokens: PreferenceNumber
  reasoningMode: string
  cacheEnabled: boolean
  parallelToolCalls: boolean
  seed: PreferenceNumber
  maximumAttempts: PreferenceNumber
}

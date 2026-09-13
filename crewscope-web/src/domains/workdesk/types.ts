/** Personal WorkDesk projection returned by the member-facing API. */
export interface WorkDeskScope {
  organizationId: string
  teamId: string
}

export const workDeskResponsibilityRoles = ['OWNER', 'EXECUTOR', 'REVIEWER'] as const
export type WorkDeskResponsibilityRole = typeof workDeskResponsibilityRoles[number]

export interface WorkDeskFilter {
  projectId?: string | null
  responsibilityRole?: WorkDeskResponsibilityRole | null
  onlyNeedsAction?: boolean
}

export interface WorkDeskItem {
  objectType: string
  objectId: string
  projectId: string | null
  title: string | null
  status: string
  updatedAt: string
  responsibilityRole: WorkDeskResponsibilityRole | null
  needsAction: boolean
  urgency: string
  progress: number | null
  availableActions: string[]
  route: string
}

export interface WorkDeskSection {
  key: string
  title: string
  priority: number
  total: number
  truncated: boolean
  items: WorkDeskItem[]
}

export interface WorkDeskSummary {
  organizationId: string
  teamId: string
  projectId: string | null
  generatedAt: string
  sections: WorkDeskSection[]
}

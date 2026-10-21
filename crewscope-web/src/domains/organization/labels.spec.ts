// @vitest-environment node
import {
  deletionItemStateLabels,
  deletionJobStatusLabels,
  deletionStepLabels,
  exportFamilyLabels,
  exportItemStateLabels,
  exportJobStatusLabels,
  organizationRoleLabels,
  organizationStatusLabels,
  roleGrantStatusLabels,
} from './labels'
import {
  deletionItemStates,
  deletionJobStatuses,
  deletionStepKeys,
  exportItemStates,
  exportJobStatuses,
  exportResourceFamilies,
  organizationRoles,
  organizationStatuses,
  roleGrantStatuses,
} from './types'

/** Every enum the organization surfaces render must have a complete, non-empty Chinese label. */
describe('organization enum labels', () => {
  it.each([
    ['organization roles', organizationRoles, organizationRoleLabels],
    ['organization statuses', organizationStatuses, organizationStatusLabels],
    ['role grant statuses', roleGrantStatuses, roleGrantStatusLabels],
    ['export job statuses', exportJobStatuses, exportJobStatusLabels],
    ['export item states', exportItemStates, exportItemStateLabels],
    ['deletion job statuses', deletionJobStatuses, deletionJobStatusLabels],
    ['deletion item states', deletionItemStates, deletionItemStateLabels],
    ['deletion step keys', deletionStepKeys, deletionStepLabels],
    ['export resource families', exportResourceFamilies, exportFamilyLabels],
  ])('covers every %s', (_name, values, labels) => {
    expect(Object.keys(labels).sort()).toEqual([...values].sort())
    for (const value of Object.values(labels)) expect(value.trim().length).toBeGreaterThan(0)
  })
})
import { evidenceNavigationPath, safeInternalPath } from './gateway'

const organizationId = '00000000-0000-4000-8000-000000000001'
const teamId = '00000000-0000-4000-8000-000000000201'
const scope = { organizationId, teamId }
const resourceId = '00000000-0000-4000-8000-000000000802'

/** Stable M6-Q01 browser route attack denominator; keep IDs and case count unchanged. */
describe('M6-Q01 fixed Team Observer route attack set', () => {
  it.each([
    ['WR-01-external-url', 'https://attacker.example/activity'],
    ['WR-02-protocol-relative', '//attacker.example/activity'],
    ['WR-03-javascript-scheme', 'javascript:alert(1)'],
    ['WR-04-data-scheme', 'data:text/html,attack'],
    ['WR-05-query', `/api/v1/organizations/${organizationId}/teams/${teamId}/activity/${resourceId}?secret=x`],
    ['WR-06-fragment', `/api/v1/organizations/${organizationId}/teams/${teamId}/activity/${resourceId}#payload`],
    ['WR-07-percent-encoding', `/api/v1/organizations/${organizationId}/teams/${teamId}/activity/%2e%2e`],
    ['WR-08-path-traversal', `/api/v1/organizations/${organizationId}/teams/${teamId}/../admin`],
    ['WR-09-backslash', `/api/v1/organizations/${organizationId}\\teams\\${teamId}`],
    ['WR-10-whitespace', `/api/v1/organizations/${organizationId}/teams/${teamId}/activity /${resourceId}`],
    ['WR-11-empty', ''],
    ['WR-12-relative-path', `api/v1/organizations/${organizationId}/teams/${teamId}/activity/${resourceId}`],
  ])('%s rejects a non-canonical internal path', (_attackId, path) => {
    expect(safeInternalPath(path)).toBe(false)
  })

  it.each([
    ['WR-13-cross-organization', `/api/v1/organizations/00000000-0000-4000-8000-000000000099/teams/${teamId}/activity/${resourceId}`],
    ['WR-14-cross-team', `/api/v1/organizations/${organizationId}/teams/00000000-0000-4000-8000-000000000299/activity/${resourceId}`],
    ['WR-15-organization-prefix-confusion', `/api/v1/organizations/${organizationId}x/teams/${teamId}/activity/${resourceId}`],
    ['WR-16-team-prefix-confusion', `/api/v1/organizations/${organizationId}/teams/${teamId}x/activity/${resourceId}`],
    ['WR-17-unapproved-audit', `/api/v1/organizations/${organizationId}/teams/${teamId}/audit-events/${resourceId}`],
    ['WR-18-unapproved-operations', `/api/v1/organizations/${organizationId}/teams/${teamId}/operations/diagnostics`],
    ['WR-19-unapproved-lark', `/api/v1/organizations/${organizationId}/teams/${teamId}/lark/connections/${resourceId}`],
    ['WR-20-unapproved-observer', `/api/v1/organizations/${organizationId}/teams/${teamId}/team-observer/sessions/${resourceId}`],
    ['WR-21-activity-invalid-id', `/api/v1/organizations/${organizationId}/teams/${teamId}/activity/not-a-uuid`],
    ['WR-22-inbox-suffix', `/api/v1/organizations/${organizationId}/teams/${teamId}/inbox/${resourceId}/raw`],
    ['WR-23-workitem-missing-project', `/api/v1/organizations/${organizationId}/teams/${teamId}/work-projects/work-items/${resourceId}`],
    ['WR-24-task-double-slash', `/api/v1/organizations/${organizationId}/teams/${teamId}/tasks//${resourceId}`],
  ])('%s rejects an unauthorized evidence target', (_attackId, path) => {
    expect(evidenceNavigationPath(path, scope)).toBeNull()
  })
})

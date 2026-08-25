import type {
  ActionBundle,
  EtaggedActionBundle,
  GitHubAuthorizationHealth,
  GitHubConnection,
  GitHubProviderBinding,
  GitHubRemotePreflight,
  GitHubRepository,
} from '../domains/delivery/types'

export const deliveryIds = {
  connection: '00000000-0000-0000-0000-000000009201',
  binding: '00000000-0000-0000-0000-000000009202',
  repository: '101',
  bundle: '00000000-0000-0000-0000-000000009203',
  confirmation: '00000000-0000-0000-0000-000000009204',
  push: '00000000-0000-0000-0000-000000009205',
  pullRequest: '00000000-0000-0000-0000-000000009206',
  pushDispatch: '00000000-0000-0000-0000-000000009207',
  pullRequestDispatch: '00000000-0000-0000-0000-000000009208',
} as const

export function githubConnection(overrides: Partial<GitHubConnection> = {}): GitHubConnection {
  return {
    id: deliveryIds.connection, ownerType: 'TEAM', teamId: crypto.randomUUID(),
    authenticationType: 'APP_INSTALLATION', executionIdentity: 'TEAM',
    externalAccountLogin: 'crewscope-labs', status: 'ACTIVE', version: 3,
    repositoryAllowlist: ['github:repository:101'], credentialStatus: 'ACTIVE',
    expiresAt: null, verifiedAt: '2026-08-25T08:00:00Z', createdAt: '2026-08-24T08:00:00Z',
    updatedAt: '2026-08-25T08:00:00Z', ...overrides,
  }
}

export function githubBinding(overrides: Partial<GitHubProviderBinding> = {}): GitHubProviderBinding {
  return {
    id: deliveryIds.binding, teamId: crypto.randomUUID(), workspaceId: crypto.randomUUID(),
    connectionId: deliveryIds.connection, connectionVersion: 3, executionIdentity: 'TEAM',
    repositoryAllowlist: ['github:repository:101'], status: 'ACTIVE', defaultUsage: true, version: 1,
    ...overrides,
  }
}

export function githubRepository(overrides: Partial<GitHubRepository> = {}): GitHubRepository {
  return {
    externalRepositoryId: deliveryIds.repository, fullName: 'crewscope/crewscope-java',
    defaultBranch: 'main', visibility: 'PRIVATE', discoveredAt: '2026-08-25T08:00:00Z',
    cacheExpiresAt: '2026-08-25T08:15:00Z', ...overrides,
  }
}

export function githubPreflight(overrides: Partial<GitHubRemotePreflight> = {}): GitHubRemotePreflight {
  return {
    connectionVersion: 3, externalRepositoryId: deliveryIds.repository,
    fullName: 'crewscope/crewscope-java', defaultBranch: 'main', permissionsHash: '1'.repeat(64),
    ...overrides,
  }
}

export function githubHealth(overrides: Partial<GitHubAuthorizationHealth> = {}): GitHubAuthorizationHealth {
  return {
    authorizationStatus: 'HEALTHY', connectionUsable: true, grantUsable: true,
    credentialUsable: true, profileCurrent: true, deliverableRepositoryCount: 1,
    webhookStatus: 'READY', rateLimit: {
      resource: 'core', limit: 5000, remaining: 4980,
      resetsAt: '2026-08-25T09:00:00Z', observedAt: '2026-08-25T08:00:00Z',
    }, ...overrides,
  }
}

export function actionBundle(overrides: Partial<ActionBundle> = {}): ActionBundle {
  return {
    id: deliveryIds.bundle, version: 0, digest: 'a'.repeat(64), validity: 'CURRENT', staleReason: null,
    taskId: crypto.randomUUID(), taskExecutionId: crypto.randomUUID(), reviewDecisionId: crypto.randomUUID(),
    repositoryBindingId: crypto.randomUUID(), repositoryKey: 'crewscope/crewscope-java',
    baselineCommit: 'b'.repeat(40), deliveryCommit: 'c'.repeat(40), confirmation: null,
    actions: [
      {
        id: deliveryIds.push, sequence: 1, kind: 'PUSH_BRANCH', risk: 'HIGH_RISK_WRITE',
        digest: 'd'.repeat(64), validUntil: '2026-08-25T09:00:00Z', dependencyActionIds: [],
        parameters: {
          repositoryId: '101', branch: 'refs/heads/crewscope/tasks/example/attempt-1',
          deliveryHead: 'c'.repeat(40), expectedRemoteHead: null, pullRequestHead: null,
          pullRequestBase: null, pullRequestHeadSha: null, title: null, body: null, draft: null,
        }, dispatch: null, receipt: null, externalResult: null,
      },
      {
        id: deliveryIds.pullRequest, sequence: 2, kind: 'CREATE_DRAFT_PR', risk: 'LOW_RISK_WRITE',
        digest: 'e'.repeat(64), validUntil: '2026-08-25T09:00:00Z', dependencyActionIds: [deliveryIds.push],
        parameters: {
          repositoryId: '101', branch: null, deliveryHead: null, expectedRemoteHead: null,
          pullRequestHead: 'crewscope/tasks/example/attempt-1', pullRequestBase: 'main',
          pullRequestHeadSha: 'c'.repeat(40), title: 'Reviewed delivery', body: 'Exact reviewed body', draft: true,
        }, dispatch: null, receipt: null, externalResult: null,
      },
    ], ...overrides,
  }
}

export function etaggedActionBundle(overrides: Partial<ActionBundle> = {}): EtaggedActionBundle {
  const value = actionBundle(overrides)
  return { value, etag: `"${value.version}"` }
}

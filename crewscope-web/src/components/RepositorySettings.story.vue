<script setup lang="ts">
import type { App } from 'vue'
import { createMemoryHistory } from 'vue-router'
import '../design/tokens.css'
import '../design/base.css'
import { CrewScopeApiError } from '../api/client'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import type { CodingGateway } from '../domains/coding/gateway'
import { CODING_STORE, createCodingStore } from '../domains/coding/store'
import type { CodingScope, RepositoryBinding } from '../domains/coding/types'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import RepositorySettingsPage from '../pages/RepositorySettingsPage.vue'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import { fixtureAuthStore } from '../test/authFixtures'

type RepositoryStoryMode = 'ready' | 'empty' | 'loading' | 'error' | 'forbidden'

const principal: AuthenticatedPrincipal = {
  id: fixtureIds.principal,
  displayName: '张凯旋',
  role: 'Team Owner',
  organizationId: fixtureIds.organization,
  organization: 'CrewScope',
  permissions: new Set(Object.values(permissions)),
}

const readySetup = setupRepositoryStory('ready')
const emptySetup = setupRepositoryStory('empty')
const loadingSetup = setupRepositoryStory('loading')
const errorSetup = setupRepositoryStory('error')
const forbiddenSetup = setupRepositoryStory('forbidden')

function setupRepositoryStory(mode: RepositoryStoryMode) {
  return async ({ app }: { app: App }): Promise<void> => {
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(principal))
    const scopeStore = createScopeStore(new FixtureScopeGateway(), principal)
    await scopeStore.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
    const codingStore = createCodingStore(repositoryGateway(mode))
    app.use(router)
    app.provide(AUTH_PRINCIPAL, principal)
    app.provide(SCOPE_STORE, scopeStore)
    app.provide(CODING_STORE, codingStore)
    await router.push(`/settings/repositories?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}`)
    await router.isReady()
  }
}

function repositoryGateway(mode: RepositoryStoryMode): CodingGateway {
  const scope: CodingScope = {
    organizationId: fixtureIds.organization,
    teamId: fixtureIds.teamPlatform,
    projectId: fixtureIds.projectCrewScope,
  }
  let binding = repositoryBinding(scope)
  const fail = (): never => {
    const status = mode === 'forbidden' ? 403 : 503
    throw new CrewScopeApiError(status, {
      code: mode === 'forbidden' ? 'policy_denied' : 'repository_unavailable',
      message: mode === 'forbidden' ? 'Repository 管理权限不足' : 'Repository 服务暂时不可用',
      correlationId: 'story-safe', retryable: status === 503, currentVersion: null, details: {},
    })
  }
  const wait = <T>(): Promise<T> => new Promise<T>(() => {})
  const unavailable = async (): Promise<never> => { throw new Error('当前 Story 未配置此操作') }

  return {
    listRepositoryCatalog: async () => {
      if (mode === 'loading') return wait()
      if (mode === 'error' || mode === 'forbidden') return fail()
      return [
        { repositoryKey: 'crewscope-java', availability: 'AVAILABLE', suggestedDefaultBranch: 'main' },
        { repositoryKey: 'agentscope-java', availability: 'AVAILABLE', suggestedDefaultBranch: 'main' },
        { repositoryKey: 'archived-service', availability: 'UNAVAILABLE', suggestedDefaultBranch: null },
      ]
    },
    listRepositoryBindings: async () => {
      if (mode === 'loading') return wait()
      if (mode === 'error' || mode === 'forbidden') return fail()
      return mode === 'empty' ? [] : [structuredClone(binding)]
    },
    getRepositoryBinding: async () => structuredClone(binding),
    createRepositoryBinding: async (_scope, input) => {
      binding = { ...binding, repositoryKey: input.repositoryKey, defaultBranch: input.defaultBranch, version: binding.version + 1 }
      return receipt(binding.version)
    },
    preflightRepositoryDraft: async (_scope, input) => ({ ready: true, repositoryKey: input.repositoryKey, baselineRef: input.defaultBranch, baselineCommit: '7'.repeat(40) }),
    preflightRepositoryBinding: async () => ({ ready: true, repositoryKey: binding.repositoryKey, baselineRef: binding.defaultBranch, baselineCommit: '7'.repeat(40) }),
    transitionRepositoryBinding: async (_scope, _id, transition) => {
      binding = { ...binding, status: transition === 'activate' ? 'ACTIVE' : 'DISABLED', version: binding.version + 1 }
      return receipt(binding.version)
    },
    listBuildProfiles: unavailable,
    preflightCodingTarget: unavailable,
    getCurrentAttempt: unavailable,
    listAttempts: unavailable,
    getAttempt: unavailable,
    listCommands: unavailable,
    listTestEvidence: unavailable,
    readPatchPage: unavailable,
    readCommandLogPage: unavailable,
    readTestReportPage: unavailable,
  }
}

function repositoryBinding(scope: CodingScope): RepositoryBinding {
  return {
    id: '00000000-0000-0000-0000-000000004101',
    organizationId: scope.organizationId,
    teamId: scope.teamId,
    workspaceId: fixtureIds.workspacePlatform,
    projectId: scope.projectId,
    kind: 'LOCAL_MANAGED',
    repositoryKey: 'crewscope-java',
    defaultBranch: 'main',
    status: 'ACTIVE',
    version: 2,
    createdAt: '2026-08-20T01:00:00Z',
    createdByPrincipalId: fixtureIds.principal,
    updatedAt: '2026-08-20T02:00:00Z',
    updatedByPrincipalId: fixtureIds.principal,
  }
}

function receipt(version: number) {
  return { commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: version, correlationId: crypto.randomUUID() }
}
</script>

<template>
  <Story title="Coding/Repository Settings" :layout="{ type: 'grid', width: 1280 }">
    <Variant title="Ready" :setup-app="readySetup"><RepositorySettingsPage /></Variant>
    <Variant title="Empty" :setup-app="emptySetup"><RepositorySettingsPage /></Variant>
    <Variant title="Loading" :setup-app="loadingSetup"><RepositorySettingsPage /></Variant>
    <Variant title="Error" :setup-app="errorSetup"><RepositorySettingsPage /></Variant>
    <Variant title="Forbidden" :setup-app="forbiddenSetup"><RepositorySettingsPage /></Variant>
  </Story>
</template>

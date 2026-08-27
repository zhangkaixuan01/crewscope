#!/usr/bin/env node

import { createHash, randomUUID } from 'node:crypto'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, isAbsolute, resolve } from 'node:path'

const confirmation = 'send-fixed-template-to-dedicated-recipient'
const dedicatedLabel = 'dedicated-lark-test-recipient'
const allowedOrigins = new Set(['https://open.feishu.cn', 'https://open.larksuite.com'])
const allowedReceiveIdTypes = new Set(['open_id', 'user_id', 'union_id', 'email', 'chat_id'])

try {
  requireExact('CREWSCOPE_M6_Q03_REAL_LARK_CONFIRM', confirmation)
  requireExact('CREWSCOPE_M6_Q03_LARK_RECIPIENT_LABEL', dedicatedLabel)
  const appId = requireSecret('CREWSCOPE_M6_Q03_LARK_APP_ID')
  const appSecret = requireSecret('CREWSCOPE_M6_Q03_LARK_APP_SECRET')
  const receiveId = requireSecret('CREWSCOPE_M6_Q03_LARK_RECEIVE_ID')
  const receiveIdType = requireValue('CREWSCOPE_M6_Q03_LARK_RECEIVE_ID_TYPE')
  if (!allowedReceiveIdTypes.has(receiveIdType)) {
    fail('receive ID type is outside the fixed allowlist')
  }

  const baseUrl = new URL(process.env.CREWSCOPE_M6_Q03_LARK_BASE_URL ?? 'https://open.feishu.cn')
  if (!allowedOrigins.has(baseUrl.origin) || baseUrl.pathname !== '/') {
    fail('Lark base URL must be an approved official origin')
  }
  const evidencePath = resolveEvidencePath(requireValue('CREWSCOPE_M6_Q03_LARK_EVIDENCE'))
  const startedAt = new Date().toISOString()

  const tokenResponse = await safeFetch(new URL('/open-apis/auth/v3/tenant_access_token/internal', baseUrl), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ app_id: appId, app_secret: appSecret }),
  }, 'tenant-token')
  const tenantToken = requiredResponseText(tokenResponse, 'tenant_access_token')

  // The body is a frozen release template. No task data, member name, repository coordinate,
  // credential, prompt, model output or arbitrary operator text can enter this request.
  const template = [
    'CrewScope Team Beta 发布候选验证',
    '模板：release-candidate-smoke@1',
    '数据集：m6-team-beta-v1',
    '种子：20260825',
    '结果：固定模板通知链路已到达专用测试接收者。',
  ].join('\n')
  const idempotencyKey = randomUUID()
  const messageUrl = new URL('/open-apis/im/v1/messages', baseUrl)
  messageUrl.searchParams.set('receive_id_type', receiveIdType)
  const messageResponse = await safeFetch(messageUrl, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${tenantToken}`,
      'Content-Type': 'application/json; charset=utf-8',
      'X-Request-Id': idempotencyKey,
    },
    body: JSON.stringify({
      receive_id: receiveId,
      msg_type: 'text',
      content: JSON.stringify({ text: template }),
      uuid: idempotencyKey,
    }),
  }, 'fixed-template-send')

  const messageId = requiredNestedText(messageResponse, ['data', 'message_id'])
  const finishedAt = new Date().toISOString()
  const evidence = {
    formatVersion: 1,
    lane: 'RELEASE_CANDIDATE',
    provider: baseUrl.origin.includes('larksuite') ? 'lark' : 'feishu',
    templateKey: 'release-candidate-smoke@1',
    dataset: 'm6-team-beta-v1',
    seed: 20260825,
    recipientLabel: dedicatedLabel,
    receiveIdType,
    appIdentityHash: sha256(appId),
    recipientIdentityHash: sha256(receiveId),
    providerMessageIdentityHash: sha256(messageId),
    idempotencyKeyHash: sha256(idempotencyKey),
    startedAt,
    finishedAt,
    outcome: 'SUCCEEDED',
  }
  mkdirSync(dirname(evidencePath), { recursive: true, mode: 0o700 })
  writeFileSync(evidencePath, `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 })
  console.log(`M6-Q03 real Lark smoke succeeded; redacted evidence written to ${evidencePath}`)
} catch (error) {
  const message = error instanceof SafeFailure ? error.message : 'unexpected smoke failure'
  console.error(`M6-Q03 real Lark smoke failed: ${message}`)
  process.exitCode = 2
}

async function safeFetch(url, init, operation) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 15_000)
  try {
    const response = await fetch(url, { ...init, redirect: 'error', signal: controller.signal })
    let body
    try {
      body = await response.json()
    } catch {
      throw new SafeFailure(`${operation} returned a non-JSON response`)
    }
    if (!response.ok || body?.code !== 0) {
      const safeCode = Number.isInteger(body?.code) ? body.code : response.status
      throw new SafeFailure(`${operation} was rejected with safe code ${safeCode}`)
    }
    return body
  } catch (error) {
    if (error instanceof SafeFailure) throw error
    if (error?.name === 'AbortError') throw new SafeFailure(`${operation} timed out`)
    throw new SafeFailure(`${operation} transport failed`)
  } finally {
    clearTimeout(timeout)
  }
}

function requiredResponseText(body, key) {
  const value = body?.[key]
  if (typeof value !== 'string' || value.length < 8) {
    throw new SafeFailure(`provider response is missing ${key}`)
  }
  return value
}

function requiredNestedText(body, path) {
  let current = body
  for (const key of path) current = current?.[key]
  if (typeof current !== 'string' || current.length === 0) {
    throw new SafeFailure('provider response is missing the safe Receipt identity')
  }
  return current
}

function requireValue(name) {
  const value = process.env[name]
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new SafeFailure(`${name} is required`)
  }
  return value.trim()
}

function requireSecret(name) {
  const value = requireValue(name)
  if (value.length < 8 || /placeholder|example|change-me/i.test(value)) {
    throw new SafeFailure(`${name} is not an acceptable short-lived credential coordinate`)
  }
  return value
}

function requireExact(name, expected) {
  if (process.env[name] !== expected) {
    throw new SafeFailure(`${name} must explicitly confirm the protected recipient boundary`)
  }
}

function resolveEvidencePath(value) {
  if (!isAbsolute(value)) throw new SafeFailure('evidence path must be absolute')
  const path = resolve(value)
  if (path === '/' || path === process.env.HOME) {
    throw new SafeFailure('evidence path is too broad')
  }
  return path
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

function fail(message) {
  throw new SafeFailure(message)
}

class SafeFailure extends Error {}

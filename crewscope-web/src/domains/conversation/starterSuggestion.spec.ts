import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { STARTER_SUGGESTION } from './starterSuggestion'

// 契约推导而非实现快照：以服务端 WELCOME_MARKDOWN 为唯一事实源，前端常量漂移即红。
const provisioningServiceSource = readFileSync(
  resolve(
    dirname(fileURLToPath(import.meta.url)),
    '../../../../crewscope-application/src/main/java/io/crewscope/application/conversation/StarterConversationProvisioningService.java',
  ),
  'utf8',
)

describe('STARTER_SUGGESTION mirrors the backend starter welcome markdown', () => {
  it('quotes the suggestion verbatim inside WELCOME_MARKDOWN', () => {
    expect(provisioningServiceSource).toContain(`「${STARTER_SUGGESTION}」`)
  })
})

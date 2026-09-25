import { readFileSync } from 'node:fs'
import ts from 'typescript'

// Keep opt-in indexes aligned with actual Gateway signatures, not an all-POST retry interceptor.
const owners = ['scope', 'workitem', 'task', 'coding', 'review', 'agent', 'model', 'teamops', 'delivery']
const paths = owners.map(name => [`domains/${name}/store.ts`, `domains/${name}/gateway.ts`])
paths.push(['domains/conversation/taskIntentStore.ts', 'domains/conversation/taskIntentGateway.ts'],
  ['domains/conversation/realtimeStore.ts', 'domains/conversation/realtimeGateway.ts'],
  ['pages/GitHubSettingsPage.vue', 'domains/delivery/gateway.ts'])

describe('explicit non-secret write inventory', () => {
  it.each(paths)('%s uses the actual idempotency-key parameter', (owner, contract) => {
    const source = readFileSync(`src/${owner}`, 'utf8')
    const gateway = ts.createSourceFile(contract!, readFileSync(`src/${contract}`, 'utf8'), ts.ScriptTarget.Latest, true)
    const members = new Map<string, ts.MethodSignature | ts.MethodDeclaration>()
    gateway.forEachChild(node => {
      if (ts.isInterfaceDeclaration(node)) for (const member of node.members) {
        if (ts.isMethodSignature(member)) members.set(member.name.getText(gateway), member)
      }
      if (ts.isClassDeclaration(node)) for (const member of node.members) {
        if (ts.isMethodDeclaration(member) && !members.has(member.name.getText(gateway))) members.set(member.name.getText(gateway), member)
      }
    })
    const whitelist = source.match(/createCommandGateway\([^\n]*?,\s*\{([^}]+)\}/s)?.[1]
    expect(whitelist).toBeTruthy()
    for (const entry of whitelist!.matchAll(/(\w+)\s*:\s*(\d+)/g)) {
      const method = members.get(entry[1]!)
      expect(method, `${owner}:${entry[1]}`).toBeDefined()
      expect(method!.parameters[Number(entry[2])]?.name.getText(gateway)).toBe('idempotencyKey')
      expect(entry[1]).not.toMatch(/login|register|stream|preflight|rotateCredential|rotateLarkConnection|createConnection|createLarkConnection|verifyLarkMember/i)
    }
  })
})

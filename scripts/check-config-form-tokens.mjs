#!/usr/bin/env node

/** M9-Q01 readability gate for configuration forms. */
import { readFile } from 'node:fs/promises'
import { resolve, relative } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const sourceRoot = resolve(root, 'crewscope-web/src')
const files = [
  'components/domain/AgentConfigurationPanel.vue',
  // The GenerateOptions inputs live in this section, not in the panel: without it here the fields a
  // member actually types numbers into were the one part of the configuration form the gate never read.
  'components/domain/AgentConfigurationPreferencesSection.vue',
  'components/domain/AgentCreateDialog.vue',
  'components/domain/ModelCredentialDialog.vue',
  'components/domain/WorkProjectCreateDialog.vue',
  'pages/AgentSettingsPage.vue',
  'pages/ModelSettingsPage.vue',
  'pages/RepositorySettingsPage.vue',
]
const offenders = []
for (const name of files) {
  const file = resolve(sourceRoot, name)
  const source = await readFile(file, 'utf8')
  for (const [index, line] of source.split(/\r?\n/).entries()) {
    const match = line.match(/font-size\s*:\s*(\d+(?:\.\d+)?)px\b/)
    if (match && Number(match[1]) < 14) offenders.push(`${relative(sourceRoot, file)}:${index + 1} (${match[1]}px)`)
  }
}
if (offenders.length) {
  console.error('M9-Q01 config-form-token gate failed:')
  offenders.forEach(item => console.error(`- ${item}`))
  process.exit(1)
}
console.log(`M9-Q01 config-form-token gate: PASS (${files.length} configuration sources scanned)`)

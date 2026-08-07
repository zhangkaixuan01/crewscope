import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, extname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const documentationRoots = [join(repositoryRoot, 'README.md'), join(repositoryRoot, 'docs')]
const markdownFiles = documentationRoots.flatMap(collectMarkdownFiles)
const failures = []

for (const markdownFile of markdownFiles) {
  const markdown = readFileSync(markdownFile, 'utf8')
  const links = markdown.matchAll(/!?\[[^\]]*]\(([^)]+)\)/g)
  for (const link of links) {
    const rawTarget = link[1].trim().replace(/^<|>$/g, '')
    const pathTarget = rawTarget.split('#', 1)[0]
    if (!pathTarget || /^(?:https?:|mailto:|data:)/i.test(pathTarget)) {
      continue
    }

    let decodedTarget
    try {
      decodedTarget = decodeURIComponent(pathTarget)
    } catch {
      failures.push(`${relativePath(markdownFile)} -> invalid URI: ${rawTarget}`)
      continue
    }
    const resolvedTarget = resolve(dirname(markdownFile), decodedTarget)
    if (!existsSync(resolvedTarget)) {
      failures.push(`${relativePath(markdownFile)} -> missing: ${decodedTarget}`)
    }
  }
}

if (failures.length > 0) {
  console.error(`Documentation link check failed (${failures.length}):`)
  for (const failure of failures) {
    console.error(`- ${failure}`)
  }
  process.exit(1)
}

console.log(`Documentation link check passed: ${markdownFiles.length} Markdown files.`)

function collectMarkdownFiles(path) {
  if (!existsSync(path)) {
    return []
  }
  if (!statSync(path).isDirectory()) {
    return extname(path) === '.md' ? [path] : []
  }
  return readdirSync(path, { withFileTypes: true })
    .flatMap(entry => collectMarkdownFiles(join(path, entry.name)))
}

function relativePath(path) {
  return path.slice(repositoryRoot.length + 1)
}

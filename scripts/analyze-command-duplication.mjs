#!/usr/bin/env node
// M13-C07 evidence: cross-file duplication across the application command services.
// Two lenses over the same files, following the M12 review method (10-line windows,
// step 5, comments and blank lines removed):
//   1. exact windows  -> byte-identical spans;
//   2. normalized windows -> identifiers, numbers and literals collapsed, so the same
//      skeleton with different names still matches.
// The output is an analysis, not a gate; it does not fail the build.

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DIR = 'crewscope-application/src/main/java/io/crewscope/application';
const FILES = [
  `${DIR}/workitem/WorkItemCommandService.java`,
  `${DIR}/workgraph/WorkItemRelationCommandService.java`,
  `${DIR}/task/MemberTaskCommandService.java`,
  `${DIR}/task/WorkerTaskCommandService.java`,
  `${DIR}/responsibility/ResponsibilityCommandService.java`,
  `${DIR}/inbox/InboxDispositionCommandService.java`,
  `${DIR}/review/ReviewLineCommentCommandService.java`,
  `${DIR}/collaboration/LarkAdministrationCommandService.java`,
  `${DIR}/knowledge/KnowledgeCommandService.java`,
  `${DIR}/skill/TeamSkillCommandService.java`,
];

const WINDOW = 10;
const STEP = 5;
const KEYWORDS = new Set(
  (
    'abstract assert boolean break byte case catch char class const continue default do double '
    + 'else enum extends final finally float for goto if implements import instanceof int interface '
    + 'long native new package private protected public return short static strictfp super switch '
    + 'synchronized this throw throws transient try void volatile while var record yield true false null'
  ).split(' '),
);

function keepLine(line) {
  const trimmed = line.trim();
  if (trimmed === '') return false;
  return !(trimmed.startsWith('//') || trimmed.startsWith('/*') || trimmed.startsWith('*'));
}

function normalize(line) {
  return line
    .trim()
    .split(/([A-Za-z_$][A-Za-z0-9_$]*|\d+|"[^"]*"|'[^']*')/g)
    .map((token) => {
      if (/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(token)) return KEYWORDS.has(token) ? token : 'ID';
      if (/^\d+$/.test(token)) return 'N';
      if (/^"[^"]*"$/.test(token)) return 'S';
      return token;
    })
    .join('')
    .replace(/\s+/g, ' ');
}

function windowsOf(relative, transform) {
  const lines = readFileSync(resolve(ROOT, relative), 'utf8').split('\n').filter(keepLine);
  const windows = [];
  for (let start = 0; start + WINDOW <= lines.length; start += STEP) {
    const text = lines.slice(start, start + WINDOW).map(transform).join('\n');
    windows.push({ hash: createHash('sha256').update(text).digest('hex'), text });
  }
  return { relative, windows };
}

function analyze(name, transform) {
  const perFile = FILES.map((relative) => windowsOf(relative, transform));
  const byHash = new Map();
  for (const file of perFile) {
    for (const window of file.windows) {
      const entry = byHash.get(window.hash) ?? { files: new Set(), occurrences: 0 };
      entry.files.add(file.relative);
      entry.occurrences += 1;
      byHash.set(window.hash, entry);
    }
  }
  const duplicated = [...byHash.values()].filter((entry) => entry.files.size >= 2);
  const pairs = new Map();
  for (const entry of duplicated) {
    const names = [...entry.files].sort();
    for (let i = 0; i < names.length; i += 1) {
      for (let j = i + 1; j < names.length; j += 1) {
        const key = `${names[i].split('/').pop()} <-> ${names[j].split('/').pop()}`;
        pairs.set(key, (pairs.get(key) ?? 0) + 1);
      }
    }
  }
  console.log(`\n=== ${name} ===`);
  for (const file of perFile) {
    const shared = file.windows.filter((window) => byHash.get(window.hash).files.size >= 2).length;
    console.log(
      `${file.relative.split('/').pop().padEnd(42)} windows=${String(file.windows.length).padStart(3)} shared=${String(shared).padStart(3)}`,
    );
  }
  const total = perFile.reduce((sum, file) => sum + file.windows.length, 0);
  console.log(
    `total windows=${total} duplicatedHashes=${duplicated.length} occurrences=${duplicated.reduce((sum, entry) => sum + entry.occurrences, 0)}`,
  );
  for (const [key, count] of [...pairs.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10)) {
    console.log(`  ${String(count).padStart(3)}  ${key}`);
  }
}

analyze('exact windows', (line) => line.trim());
analyze('identifier-normalized windows', normalize);

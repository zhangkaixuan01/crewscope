// Executable S01 design model. Not imported by the product or a replacement for its policies.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import path from 'node:path';
import fs from 'node:fs';

export const nodeImage = 'node@sha256:71d7f07420e0acab162781f0bae22c18a1d04d4704594a6cd7d1f5cbb87cb0d7';
export const nodeProfile = Object.freeze({
  schemaVersion: 2, key: 'node24-npm11', version: 1,
  runtime: 'NODE', runtimeVersion: '24.19.0',
  packageManager: 'NPM', packageManagerVersion: '11.17.0',
  image: nodeImage, directory: '.', defaultTimeout: 60, maxTimeout: 900,
  prepare: ['npm', 'ci', '--ignore-scripts', '--no-audit', '--no-fund'],
  commands: { COMPILE: ['npm', 'run', 'build'], TEST: ['npm', 'run', 'test'] },
});

export function canonicalProfile(profile) {
  assert.deepEqual(Object.keys(profile).sort(), Object.keys(nodeProfile).sort(), 'unknown/missing profile field');
  assert.equal(profile.schemaVersion, 2);
  assert.match(profile.key, /^[a-z][a-z0-9-]{1,63}$/);
  assert(Number.isSafeInteger(profile.version) && profile.version > 0);
  assert.equal(profile.runtime, 'NODE');
  assert.equal(profile.runtimeVersion, '24.19.0');
  assert.equal(profile.packageManager, 'NPM');
  assert.equal(profile.packageManagerVersion, '11.17.0');
  assert.equal(profile.image, nodeImage, 'image must be the approved sample digest');
  assert.equal(typeof profile.directory, 'string');
  assert(profile.directory === '.' || /^(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_-]+$/.test(profile.directory));
  assert(Number.isSafeInteger(profile.defaultTimeout) && Number.isSafeInteger(profile.maxTimeout));
  assert(profile.defaultTimeout > 0 && profile.defaultTimeout <= profile.maxTimeout && profile.maxTimeout <= 900);
  assert.deepEqual(profile.prepare, nodeProfile.prepare);
  assert.deepEqual(Object.keys(profile.commands).sort(), ['COMPILE', 'TEST']);
  assert.deepEqual(profile.commands.COMPILE, ['npm', 'run', 'build']);
  assert.deepEqual(profile.commands.TEST, ['npm', 'run', 'test']);
  return 'build-profile-v2\n' + JSON.stringify([
    profile.key, profile.version, profile.runtime, profile.runtimeVersion,
    profile.packageManager, profile.packageManagerVersion, profile.image,
    profile.directory, profile.defaultTimeout, profile.maxTimeout, profile.prepare,
    Object.entries(profile.commands).sort(([a], [b]) => a.localeCompare(b)),
  ]);
}

export function profileHash(profile) {
  return createHash('sha256').update(canonicalProfile(profile)).digest('hex');
}

export function resolveDirectory(root, relative) {
  canonicalProfile({ ...nodeProfile, directory: relative });
  const actualRoot = fs.realpathSync(root);
  const actual = fs.realpathSync(path.resolve(actualRoot, relative));
  const back = path.relative(actualRoot, actual);
  assert(!back.startsWith('..') && !path.isAbsolute(back), 'symlink escaped repository');
  return actual;
}

export function selectedExecution(query) {
  for (const key of ['taskExecution', 'attempt']) {
    assert(query[key] == null || (typeof query[key] === 'string' && query[key].trim().length > 0), 'invalid execution coordinate');
  }
  assert(!query.taskExecution || !query.attempt || query.taskExecution === query.attempt, 'conflicting aliases');
  return query.taskExecution ?? query.attempt ?? null;
}

export const routeQueries = {
  today: ['team', 'deskProject', 'deskRole', 'deskAction', 'deskGroup'],
  work: ['team', 'project', 'workItem', 'task', 'taskExecution', 'attempt', 'workspace', 'review', 'focus', 'delegate', 'view', 'status', 'type', 'priority', 'sort', 'direction', 'taskStatus', 'taskOwner', 'conversation', 'sourceMessage'],
  conversation: ['team', 'conversation', 'assistant', 'focus'],
  inbox: ['team', 'inboxType', 'sourceStatus', 'disposition', 'inboxItem'],
  activity: ['team', 'actor', 'category', 'event', 'sortDirection'],
  search: ['team', 'project', 'q', 'types'],
  audit: ['team', 'from', 'to', 'category', 'outcome', 'initiator', 'actor', 'agent', 'subjectId', 'subjectType', 'providerBinding', 'correlation', 'auditEvent', 'chain'],
  'agent-settings': ['team', 'agent', 'configurationRevision', 'returnContext'],
  'model-settings': ['team', 'provider', 'connection', 'ownerType', 'returnContext'],
  'repository-settings': ['team', 'project', 'binding', 'returnContext'],
  'github-settings': ['team', 'project', 'connection', 'importJob', 'returnContext'],
  'lark-settings': ['team', 'tab', 'connection', 'mappingStatus', 'deliveryStatus', 'deliveryType', 'recipient', 'delivery', 'returnContext'],
  setup: ['team', 'project', 'goal', 'returnContext'],
  'team-members': ['team', 'member', 'invitation', 'tab'],
  'team-observer': ['team'], operations: ['team'], account: [],
  login: ['returnTo'], register: [], invite: [], onboarding: [],
  'access-denied': [], 'not-found': [],
};

// Parameter ownership only; product DTO validation/authorization remains mandatory.
export function ownQuery(route, query) {
  assert(Object.hasOwn(routeQueries, route), 'unknown route');
  return Object.fromEntries(Object.entries(query).filter(([key]) => routeQueries[route].includes(key)));
}

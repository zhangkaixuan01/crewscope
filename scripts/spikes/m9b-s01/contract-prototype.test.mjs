import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { canonicalProfile, nodeProfile, ownQuery, profileHash, routeQueries, selectedExecution } from './contract-prototype.mjs';

test('F01/A01 handoff freezes result recovery without claiming a production resolver', () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url));
  const contract = fs.readFileSync(root + 'docs/spikes/M9b-S01-核心流程与合同冻结.md', 'utf8');
  const section = contract.split('### 3.1')[1].split('### 3.3')[0];
  for (const invariant of [
    'GET /api/v1/organizations/{organizationId}/command-results', 'Idempotency-Key',
    '原 actor 相同', '同一事务', 'Cache-Control: no-store', '404',
    '不解释为原命令没发生', '不生成新键重试', '最多 8 次', '30 秒',
    '离页/卸载停止自动轮询', '唯一坐标', '秘密永不持久化', '不扩展旧',
  ]) assert(section.includes(invariant), `result handoff lost invariant: ${invariant}`);
  assert(section.includes('待实现'));
});

test('v2 typed profile survives JSON round trip and command order changes', () => {
  const decoded = JSON.parse(JSON.stringify(nodeProfile));
  assert.equal(profileHash(decoded), profileHash(nodeProfile));
  assert.equal(profileHash({ ...decoded, commands: { TEST: decoded.commands.TEST, COMPILE: decoded.commands.COMPILE } }), profileHash(nodeProfile));
  assert.notEqual(profileHash({ ...decoded, version: 2 }), profileHash(nodeProfile));
  assert(canonicalProfile(decoded).startsWith('build-profile-v2\n'));
  assert.equal(profileHash(decoded), '613d1ccbc6bd5a2d2ddde220d1d41ad7e48c12d4ce257a2eb3b9b363b0259fe9');
});

test('unsupported runtime, package manager, script, digest, selectors and directories fail closed', () => {
  const invalid = [
    { runtime: 'PYTHON' }, { packageManager: 'PNPM' }, { runtimeVersion: 'latest' },
    { image: 'node:24-alpine' }, { javaRelease: 17 }, { version: 0 },
    { maxTimeout: 3601 }, { prepare: ['sh', '-c', 'npm ci'] },
    { commands: { TEST: ['npm', 'exec', 'arbitrary'], COMPILE: ['npm', 'run', 'build'] } },
    ...['../secret', '/etc', 'a/../b', 'a\\b', 'a\nrun', 'a;echo'].map(directory => ({ directory })),
  ];
  for (const change of invalid) assert.throws(() => canonicalProfile({ ...nodeProfile, ...change }));
});

test('execution aliases never choose a different execution when explicit coordinates conflict', () => {
  assert.equal(selectedExecution({ attempt: 'old-link' }), 'old-link');
  assert.equal(selectedExecution({ taskExecution: 'a', attempt: 'a' }), 'a');
  assert.equal(selectedExecution({}), null);
  assert.throws(() => selectedExecution({ taskExecution: 'a', attempt: 'b' }));
  assert.throws(() => selectedExecution({ attempt: ['a', 'b'] }));
  assert.throws(() => selectedExecution({ taskExecution: '' }));
});

test('target whitelist isolates account/settings and preserves old search deep links', () => {
  assert.deepEqual(ownQuery('account', { team: 't', workItem: 'w', token: 'secret' }), {});
  assert.deepEqual(ownQuery('model-settings', { team: 't', workItem: 'w', connection: 'c' }), { team: 't', connection: 'c' });
  assert.deepEqual(ownQuery('repository-settings', { team: 't', project: 'p', binding: 'b' }), { team: 't', project: 'p', binding: 'b' });
  assert.deepEqual(ownQuery('team-members', { member: 'm' }), { member: 'm' });
  assert.deepEqual(ownQuery('invite', { token: 'secret', returnTo: '//evil' }), {});
});

test('every current page has a frozen route contract', () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url));
  const router = fs.readFileSync(root + 'crewscope-web/src/app/router.ts', 'utf8');
  const names = [...router.matchAll(/name: '([^']+)',\n\s+component:/g)].map(match => match[1]);
  assert.equal(names.length, 23);
  assert.deepEqual(names.sort(), Object.keys(routeQueries).sort());
});

test('decision and scope inventory stays complete without replacing functional acceptance', () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url));
  const read = name => fs.readFileSync(root + name, 'utf8');
  const plan = read('docs/plans/M9b-核心流程与使用体验收口.md');
  const contract = read('docs/plans/M9b-实现边界与验收契约.md');
  const spike = read('docs/spikes/M9b-S01-核心流程与合同冻结.md');
  const detail = read('docs/spikes/M9b-S01-接口权限与查询冻结.md');
  for (let i = 1; i <= 43; i++) assert(plan.includes(`R${String(i).padStart(2, '0')}`));
  for (let i = 1; i <= 16; i++) assert(contract.includes(`C${String(i).padStart(2, '0')}`));
  for (let i = 1; i <= 8; i++) assert(spike.includes(`| D${String(i).padStart(2, '0')} `));
  for (let i = 1; i <= 17; i++) assert(spike.includes(`| ${i} `));
  for (const parent of ['A01','A02','A03','A04','A05','A06','A07','F01','F02','F03','F04','F05','Q01','Q02']) {
    assert((spike + detail).includes(`${parent}/`), `missing implementation card ${parent}`);
  }
  assert(read('docs/adr/ADR-038-团队成员生命周期与责任转移.md').includes('状态：ACCEPTED'));
  assert(spike.includes('没有修改生产领域/API/数据库迁移或部署配置'));
});

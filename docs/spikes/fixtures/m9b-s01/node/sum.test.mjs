import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sum } from './sum.mjs';

test('offline sample produces the expected result', () => {
  assert.equal(sum(2, 3), 5);
});

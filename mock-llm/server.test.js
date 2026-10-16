import { test } from 'node:test';
import assert from 'node:assert/strict';
import { embed, parseChunks, buildAnswer } from './server.js';

test('embeddings are deterministic, normalised and sized', () => {
  const a = embed('annual leave policy', 384);
  assert.equal(a.length, 384);
  assert.deepEqual(a, embed('annual leave policy', 384));
  const norm = Math.sqrt(a.reduce((s, x) => s + x * x, 0));
  assert.ok(Math.abs(norm - 1) < 1e-9);
});

test('parses delimited chunks and answers from the first', () => {
  const ctx = '<<<DOCUMENT documentId="d1" chunkIndex="3" title="Leave" section="">>>\nYou get 20 days.\n<<<END DOCUMENT>>>';
  assert.deepEqual(parseChunks(ctx)[0], { documentId: 'd1', chunkIndex: 3, title: 'Leave', content: 'You get 20 days.' });
  assert.match(buildAnswer([{ role: 'user', content: ctx }]).text, /20 days/);
});

import { describe, it, expect } from 'vitest';
import { createSseParser } from './sse.js';

describe('createSseParser', () => {
  it('handles events split across arbitrary chunks', () => {
    const events = [];
    const p = createSseParser((e) => events.push(e));
    const raw = 'event: token\ndata: {"t":"Hel"}\n\nevent: token\ndata: {"t":"lo"}\n\nevent: done\ndata: {}\n\n';
    for (const ch of raw) p.feed(ch); // one character at a time
    expect(events).toEqual([
      { event: 'token', data: '{"t":"Hel"}' },
      { event: 'token', data: '{"t":"lo"}' },
      { event: 'done', data: '{}' },
    ]);
  });

  it('supports CRLF, comments, no-space values and multi-line data', () => {
    const events = [];
    const p = createSseParser((e) => events.push(e));
    p.feed(': keepalive\r\n\r\nevent:token\r\ndata:a\r\ndata:b\r\n\r\n');
    expect(events).toEqual([{ event: 'token', data: 'a\nb' }]);
  });

  it('flush emits a trailing event without blank line', () => {
    const events = [];
    const p = createSseParser((e) => events.push(e));
    p.feed('data: x');
    expect(events).toEqual([]);
    p.flush();
    expect(events).toEqual([{ event: 'message', data: 'x' }]);
  });
});

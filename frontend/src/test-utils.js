const enc = new TextEncoder();

/** Fake fetch Response whose body yields the given string chunks; honours AbortSignal like real fetch. */
export function streamResponse(chunks, { signal, hang = false } = {}) {
  let i = 0;
  return {
    ok: true,
    status: 200,
    body: {
      getReader() {
        return {
          read() {
            if (signal?.aborted) return Promise.reject(new DOMException('Aborted', 'AbortError'));
            if (i < chunks.length) return Promise.resolve({ value: enc.encode(chunks[i++]), done: false });
            if (!hang) return Promise.resolve({ value: undefined, done: true });
            return new Promise((_, reject) => {
              signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
            });
          },
        };
      },
    },
  };
}

export function jsonResponse(body, status = 200) {
  return { ok: status < 400, status, json: () => Promise.resolve(body) };
}

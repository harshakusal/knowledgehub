import { useEffect, useRef, useState } from 'react';
import { apiFetch, errorMessage } from '../lib/api.js';
import { createSseParser } from '../lib/sse.js';

/**
 * Streams an answer from POST /api/chat/stream. EventSource can't POST, so we use fetch + body.getReader().
 * status: idle | streaming | done | stopped | error
 */
export default function ChatPanel({ userId, documentIds = [] }) {
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState('');
  const [sources, setSources] = useState([]);
  const [status, setStatus] = useState('idle');
  const [error, setError] = useState(null);
  const controllerRef = useRef(null);

  // Abort any in-flight stream when the component unmounts (or the user changes).
  useEffect(() => () => controllerRef.current?.abort(), []);

  async function ask(e) {
    e.preventDefault();
    const q = question.trim();
    if (!q || status === 'streaming') return;
    const controller = new AbortController();
    controllerRef.current = controller;
    setAnswer('');
    setSources([]);
    setError(null);
    setStatus('streaming');
    let finished = false;
    try {
      const res = await apiFetch('/api/chat/stream', userId, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({ question: q, documentIds }),
        signal: controller.signal,
      });
      if (!res.ok) throw new Error(await errorMessage(res));
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      const parser = createSseParser(({ event, data }) => {
        if (event === 'token') {
          const { t } = JSON.parse(data);
          setAnswer((prev) => prev + t);
        } else if (event === 'sources') {
          setSources(JSON.parse(data));
        } else if (event === 'error') {
          finished = true;
          setError(JSON.parse(data).message || 'The assistant failed');
          setStatus('error');
        } else if (event === 'done') {
          finished = true;
          setStatus('done');
        }
      });
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        parser.feed(decoder.decode(value, { stream: true }));
      }
      parser.feed(decoder.decode());
      parser.flush();
      if (!finished) {
        setError('Connection closed before the answer finished');
        setStatus('error');
      }
    } catch (err) {
      if (err.name === 'AbortError') {
        setStatus((s) => (s === 'streaming' ? 'stopped' : s));
      } else {
        setError(err.message || 'Network error');
        setStatus('error');
      }
    } finally {
      if (controllerRef.current === controller) controllerRef.current = null;
    }
  }

  function stop() {
    controllerRef.current?.abort();
  }

  const streaming = status === 'streaming';
  return (
    <section aria-label="Chat">
      <h2>Ask a question</h2>
      <p className="muted">
        {documentIds.length ? `Searching ${documentIds.length} selected document(s)` : 'Searching all your documents'}
      </p>
      <form onSubmit={ask}>
        <textarea
          aria-label="Question"
          value={question}
          maxLength={2000}
          disabled={streaming}
          onChange={(e) => setQuestion(e.target.value)}
        />
        <div>
          <button type="submit" disabled={streaming || !question.trim()}>Ask</button>
          <button type="button" onClick={stop} disabled={!streaming}>Stop</button>
          {streaming && <span className="muted" role="status">Generating…</span>}
          {status === 'stopped' && <span className="muted" role="status">Stopped</span>}
        </div>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="answer" data-testid="answer" aria-live="polite">{answer}</div>
      {sources.length > 0 && (
        <p className="muted">
          Sources:{' '}
          {sources.map((s) => (
            <span key={`${s.documentId}-${s.chunkIndex}`}>[{s.documentId.slice(0, 8)} #{s.chunkIndex}] </span>
          ))}
        </p>
      )}
    </section>
  );
}

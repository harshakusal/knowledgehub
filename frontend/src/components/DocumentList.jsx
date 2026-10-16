import { useCallback, useEffect, useRef, useState } from 'react';
import { apiFetch, errorMessage } from '../lib/api.js';

const POLL_MS = 3000;
const DEBOUNCE_MS = 300;

/** Lists the current user's documents, supports search, upload, delete and async summaries. */
export default function DocumentList({ userId, selectedIds = [], onToggleSelect = () => {} }) {
  const [query, setQuery] = useState('');
  const [debounced, setDebounced] = useState('');
  const [docs, setDocs] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [summaries, setSummaries] = useState({});
  const [reloadKey, setReloadKey] = useState(0);
  const fileRef = useRef(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  // Debounce typing so we don't fire a request per keystroke.
  useEffect(() => {
    const t = setTimeout(() => setDebounced(query.trim()), DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [query]);

  // Fetch: async function INSIDE the effect, AbortController cancels stale requests (no out-of-order results),
  // and the list is REPLACED, never appended.
  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      setLoading(true);
      try {
        const res = await apiFetch(`/api/documents?q=${encodeURIComponent(debounced)}`, userId, { signal: controller.signal });
        if (!res.ok) throw new Error(await errorMessage(res));
        setDocs(await res.json());
        setError(null);
      } catch (err) {
        if (err.name !== 'AbortError') setError(err.message);
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    }
    load();
    return () => controller.abort();
  }, [debounced, userId, reloadKey]);

  // Poll only while something is still being ingested; interval is always cleared.
  const hasPending = docs.some((d) => d.status === 'PENDING');
  useEffect(() => {
    if (!hasPending) return undefined;
    const id = setInterval(() => setReloadKey((k) => k + 1), POLL_MS);
    return () => clearInterval(id);
  }, [hasPending]);

  async function upload(e) {
    e.preventDefault();
    const file = fileRef.current?.files?.[0];
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    const res = await apiFetch('/api/documents', userId, { method: 'POST', body: form });
    if (!res.ok) {
      setError(await errorMessage(res));
      return;
    }
    fileRef.current.value = '';
    setReloadKey((k) => k + 1);
  }

  async function remove(id) {
    const res = await apiFetch(`/api/documents/${encodeURIComponent(id)}`, userId, { method: 'DELETE' });
    if (!res.ok) {
      setError(await errorMessage(res));
      return;
    }
    if (selectedIds.includes(id)) onToggleSelect(id);
    setReloadKey((k) => k + 1);
  }

  const summarize = useCallback(async (id) => {
    setSummaries((s) => ({ ...s, [id]: { status: 'QUEUED' } }));
    const res = await apiFetch(`/api/documents/${encodeURIComponent(id)}/summary`, userId, { method: 'POST' });
    if (!res.ok) {
      setSummaries((s) => ({ ...s, [id]: { status: 'FAILED', error: 'Could not start summary' } }));
      return;
    }
    const { jobId } = await res.json();
    for (let i = 0; i < 60 && mounted.current; i++) {
      await new Promise((r) => setTimeout(r, 1000));
      const jr = await apiFetch(`/api/jobs/${encodeURIComponent(jobId)}`, userId);
      if (!jr.ok) break;
      const job = await jr.json();
      if (!mounted.current) return;
      setSummaries((s) => ({ ...s, [id]: job }));
      if (job.status === 'SUCCEEDED' || job.status === 'FAILED') return;
    }
  }, [userId]);

  return (
    <section aria-label="Documents">
      <h2>My documents</h2>
      <form onSubmit={upload}>
        <input type="file" accept=".txt,.md" aria-label="Upload file" ref={fileRef} />
        <button type="submit">Upload</button>
      </form>
      <input
        type="search"
        placeholder="Search titles"
        aria-label="Search documents"
        value={query}
        maxLength={100}
        onChange={(e) => setQuery(e.target.value)}
      />
      {loading && <span className="muted"> loading…</span>}
      {error && <p className="error" role="alert">{error}</p>}
      <ul>
        {docs.map((d) => (
          <li key={d.id}>
            <label>
              <input type="checkbox" checked={selectedIds.includes(d.id)} onChange={() => onToggleSelect(d.id)} />
              {/* Rendered as text: React escapes it, so a malicious title can't inject HTML. */}
              {d.title}
            </label>{' '}
            <span className="muted">{d.status}</span>
            <div>
              <button type="button" onClick={() => summarize(d.id)} disabled={d.status !== 'READY'}>Summarize</button>
              <button type="button" onClick={() => remove(d.id)}>Delete</button>
            </div>
            {summaries[d.id] && (
              <p className="muted" style={{ whiteSpace: 'pre-wrap' }}>
                {summaries[d.id].status === 'SUCCEEDED' ? summaries[d.id].result
                  : summaries[d.id].status === 'FAILED' ? `Summary failed: ${summaries[d.id].error ?? ''}`
                  : 'Summarizing…'}
              </p>
            )}
          </li>
        ))}
      </ul>
      {!loading && docs.length === 0 && <p className="muted">No documents yet.</p>}
    </section>
  );
}

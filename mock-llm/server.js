// Mock LLM server (zero dependencies).
//   POST /v1/embeddings  -> deterministic hashed bag-of-words vectors (EMBEDDING_DIM, default 384), L2-normalised
//   POST /v1/chat        -> {stream:true}: SSE tokens `data: {"choices":[{"delta":{"content":"..."}}]}` then `data: [DONE]`
//                           {stream:false}: {"choices":[{"message":{"content":"..."}}]} (JSON answer if response_format json_object)
// Chaos: random 429 (with Retry-After) and 503, controlled by FAIL_RATE_429 / FAIL_RATE_503.
import http from 'node:http';

const PORT = Number(process.env.PORT ?? 4000);
const DIM = Number(process.env.EMBEDDING_DIM ?? 384);
const RATE_429 = Number(process.env.FAIL_RATE_429 ?? 0.1);
const RATE_503 = Number(process.env.FAIL_RATE_503 ?? 0.05);
const TOKEN_DELAY_MS = Number(process.env.TOKEN_DELAY_MS ?? 40);
const RETRY_AFTER_S = Number(process.env.RETRY_AFTER_S ?? 1);

export function hash(str) {
  let h = 2166136261;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

export function embed(text, dim = DIM) {
  const v = new Array(dim).fill(0);
  const words = String(text).toLowerCase().match(/[a-z0-9]+/g) ?? [];
  for (const w of words) {
    const h = hash(w);
    v[h % dim] += (h & 1) ? 1 : -1;
  }
  const norm = Math.sqrt(v.reduce((s, x) => s + x * x, 0)) || 1;
  if (words.length === 0) v[0] = 1;
  return v.map((x) => (words.length === 0 ? x : x / norm));
}

export function parseChunks(text) {
  const re = /<<<DOCUMENT documentId="([^"]+)" chunkIndex="(\d+)"[^>]*title="([^"]*)"[^>]*>>>\n([\s\S]*?)\n<<<END DOCUMENT>>>/g;
  const out = [];
  let m;
  while ((m = re.exec(text)) !== null) out.push({ documentId: m[1], chunkIndex: Number(m[2]), title: m[3], content: m[4] });
  return out;
}

export function buildAnswer(messages) {
  const user = [...messages].reverse().find((m) => m.role === 'user')?.content ?? '';
  const all = messages.map((m) => m.content).join('\n');
  const chunks = parseChunks(all);
  if (chunks.length === 0) {
    const doc = /<<<DOCUMENT[^>]*>>>\n([\s\S]*?)\n<<<END DOCUMENT>>>/.exec(all);
    if (doc) {
      const words = doc[1].split(/\s+/).filter(Boolean);
      return { text: `- ${words.slice(0, 25).join(' ')}...\n- (${words.length} words in total)`, chunks };
    }
    return { text: `I don't have any documents to answer: ${user.slice(0, 80)}`, chunks };
  }
  const top = chunks[0];
  const words = top.content.split(/\s+/).filter(Boolean).slice(0, 60).join(' ');
  return { text: `According to "${top.title}": ${words}`, chunks };
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > 10_000_000) req.destroy(); });
    req.on('end', () => { try { resolve(data ? JSON.parse(data) : {}); } catch (e) { reject(e); } });
    req.on('error', reject);
  });
}

function send(res, status, body, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json', ...headers });
  res.end(JSON.stringify(body));
}

function chaos(res) {
  const r = Math.random();
  if (r < RATE_429) {
    send(res, 429, { error: 'rate_limited' }, { 'Retry-After': String(RETRY_AFTER_S) });
    return true;
  }
  if (r < RATE_429 + RATE_503) {
    send(res, 503, { error: 'unavailable' });
    return true;
  }
  return false;
}

export function createServer() {
  return http.createServer(async (req, res) => {
    if (req.method === 'GET' && req.url === '/health') return send(res, 200, { ok: true });
    if (req.method !== 'POST') return send(res, 404, { error: 'not_found' });
    let body;
    try { body = await readBody(req); } catch { return send(res, 400, { error: 'invalid_json' }); }
    if (chaos(res)) return;

    if (req.url === '/v1/embeddings') {
      const input = Array.isArray(body.input) ? body.input : [body.input];
      if (input.some((i) => typeof i !== 'string')) return send(res, 400, { error: 'input must be string(s)' });
      return send(res, 200, { object: 'list', data: input.map((t, index) => ({ object: 'embedding', index, embedding: embed(t) })) });
    }

    if (req.url === '/v1/chat') {
      if (!Array.isArray(body.messages)) return send(res, 400, { error: 'messages required' });
      const { text, chunks } = buildAnswer(body.messages);
      if (!body.stream) {
        let content = text;
        if (body.response_format?.type === 'json_object') {
          content = JSON.stringify(chunks.length
            ? { answer: text, citations: [{ documentId: chunks[0].documentId, chunkIndex: chunks[0].chunkIndex }], confidence: 'medium', answerFound: true }
            : { answer: 'Not found in the provided documents.', citations: [], confidence: 'low', answerFound: false });
        }
        return send(res, 200, { choices: [{ index: 0, message: { role: 'assistant', content } }] });
      }
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      const tokens = text.match(/\S+\s*/g) ?? [];
      let i = 0;
      let closed = false;
      res.on('close', () => {
        if (i < tokens.length) console.log(`[mock-llm] client disconnected after ${i}/${tokens.length} tokens`);
        closed = true;
      });
      const tick = () => {
        if (closed) return;
        if (i >= tokens.length) {
          res.write('data: [DONE]\n\n');
          console.log(`[mock-llm] stream completed (${tokens.length} tokens)`);
          return res.end();
        }
        res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: tokens[i++] } }] })}\n\n`);
        setTimeout(tick, TOKEN_DELAY_MS);
      };
      return tick();
    }
    send(res, 404, { error: 'not_found' });
  });
}

if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  createServer().listen(PORT, () => console.log(`[mock-llm] listening on :${PORT} dim=${DIM} 429=${RATE_429} 503=${RATE_503}`));
}

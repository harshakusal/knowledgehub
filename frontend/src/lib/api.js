// Stub auth: the backend trusts X-User-Id (see README). Real deployments put an IdP/gateway in front.
export function apiFetch(path, userId, options = {}) {
  return fetch(path, {
    ...options,
    headers: { ...(options.headers ?? {}), 'X-User-Id': userId },
  });
}

export async function errorMessage(res) {
  try {
    const body = await res.json();
    return body.detail || body.title || `Request failed (${res.status})`;
  } catch {
    return `Request failed (${res.status})`;
  }
}

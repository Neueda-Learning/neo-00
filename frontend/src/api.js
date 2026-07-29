// Same-origin paths: nginx proxies /api, /health and /info to the orchestrator in the
// container, and Vite proxies them in `npm run dev`.
const BASE = import.meta.env.VITE_API_BASE || '';

async function request(path, options = {}) {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const body = await res.json();
      if (body.message) message = body.message;
    } catch {
      /* non-JSON error body */
    }
    throw new Error(message);
  }
  if (res.status === 204) return null;
  return res.json();
}

export const api = {
  health: () => request('/health'),
  info: () => request('/info'),

  board: (limit = 200) => request(`/api/v1/applications?limit=${limit}`),
  summary: () => request('/api/v1/applications/summary'),
  // The journey view: board row + application + the full event log. The bare
  // /applications/{id} is the api-contract §4 application object, which the ten
  // services read — this screen wants the events too.
  journey: (id) => request(`/api/v1/applications/${id}/journey`),
  // Backoffice "+ one": no body, the orchestrator generates a fixture applicant.
  createApplication: () => request('/api/v1/applications', { method: 'POST' }),
  // Customer journey: the attendee's filled-in Application object (api-contract §4 shape).
  submitApplication: (application) =>
    request('/api/v1/applications', {
      method: 'POST',
      body: JSON.stringify(application),
    }),

  events: (serviceId, limit = 200) =>
    request(
      `/api/v1/events?limit=${limit}` + (serviceId ? `&serviceId=${serviceId}` : '')
    ),

  services: () => request('/api/v1/services'),

  generator: () => request('/api/v1/generator'),
  setGenerator: (body) =>
    request('/api/v1/generator', { method: 'POST', body: JSON.stringify(body) }),

  // Demo stepping. While it is on, no step leaves the orchestrator until someone
  // presses Proceed — see the orchestrator's SagaEngine.
  demoMode: () => request('/api/v1/demo-mode'),
  setDemoMode: (enabled) =>
    request('/api/v1/demo-mode', {
      method: 'POST',
      body: JSON.stringify({ enabled }),
    }),
  // Send the step a parked journey is waiting on. 409 if it is not parked, which the
  // request helper surfaces as the orchestrator's own message.
  proceed: (id) => request(`/api/v1/applications/${id}/proceed`, { method: 'POST' }),
};

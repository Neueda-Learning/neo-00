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
  // Who is applying rides as a query parameter, not a field of the body — the body is the object
  // ten modules bind into typed records, and it is not ours to add keys to.
  submitApplication: (application, customerId) =>
    request(
      '/api/v1/applications' + (customerId ? `?customerId=${customerId}` : ''),
      { method: 'POST', body: JSON.stringify(application) }
    ),

  // ---- signing in ----

  // Idempotent: creates the code if it is new, and either way returns everything that customer
  // has. `isNew` off THIS response is what the greeting reads — never the typing hint below,
  // which is a separate request and can disagree with it.
  signIn: (code) => request(`/api/v1/customers/${code}`, { method: 'PUT' }),
  // What a known customer has. Throws on 404, which is how the login hint tells "already in use"
  // from "free" — see LoginScreen for why only a 404 may be read that way.
  customer: (code) => request(`/api/v1/customers/${code}`),

  events: (serviceId, limit = 200) =>
    request(
      `/api/v1/events?limit=${limit}` + (serviceId ? `&serviceId=${serviceId}` : '')
    ),

  services: () => request('/api/v1/services'),

  // The live product catalogue, proxied from the module that owns it. An empty list means that
  // module is unreachable — the picker falls back to its own copy rather than showing nothing.
  products: () => request('/api/v1/products'),

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

  // ---- the customer's own two actions ----
  //
  // Both go through the orchestrator to the module that owns them. A browser could call those
  // modules directly, but then this page would need their addresses, their CORS policies would
  // have to admit it, and on AWS the addresses are different again.

  // The agreement's terms and whether it is this customer's to sign yet.
  agreement: (id) => request(`/api/v1/applications/${id}/agreement`),
  productDetails: (id) => request(`/api/v1/applications/${id}/product-details`),
  // A URL rather than a fetch: the PDF is for an <iframe> to load, not for us to hold in memory.
  agreementDocumentUrl: (id) => `${BASE}/api/v1/applications/${id}/agreement/document`,
  // Neither of these advances the journey. They report a fact to the module that owns the
  // agreement; whether the journey moves is its answer, sent back the ordinary way.
  signAgreement: (id) => request(`/api/v1/applications/${id}/agreement/sign`, { method: 'POST' }),
  declineAgreement: (id) =>
    request(`/api/v1/applications/${id}/agreement/decline`, { method: 'POST' }),

  // Open a support case about a finished application. One per application: the support module
  // derives its case id from the correlation id, so a second send returns the first case.
  openSupportCase: (id, body) =>
    request(`/api/v1/applications/${id}/support-case`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  // The case this customer already has, so the page can keep it open and show what the bank has
  // said back. `null` (a 204) means they have none and should be offered the form.
  supportCase: (id) => request(`/api/v1/applications/${id}/support-case`),

  // Instructor simulator. These calls go through the orchestrator because it owns the generated
  // application id and can therefore pair the module's ordinary callback without polluting the
  // journey board. Targets are configured server-side; the browser never supplies a URL.
  scenarios: () => request('/api/v1/simulator/scenarios'),
  targets: () => request('/api/v1/simulator/targets'),
  dispatch: (body) =>
    request('/api/v1/simulator/dispatch', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  dispatches: (target) =>
    request('/api/v1/simulator/dispatches' + (target ? `?target=${encodeURIComponent(target)}` : '')),
  clearDispatches: (target) =>
    request(`/api/v1/simulator/dispatches?target=${encodeURIComponent(target)}`, {
      method: 'DELETE',
    }),
};

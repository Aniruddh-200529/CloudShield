const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";
let csrfToken = "";

export async function refreshCsrf() {
  const response = await fetch(`${API_BASE}/api/auth/csrf`, { credentials: "include" });
  if (!response.ok) throw new Error("Could not initialize secure session");
  csrfToken = (await response.json()).token;
}

export async function api(path, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  if (!["GET", "HEAD", "OPTIONS"].includes(method) && !csrfToken) await refreshCsrf();
  const headers = new Headers(options.headers || {});
  if (options.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) headers.set("X-XSRF-TOKEN", csrfToken);
  const response = await fetch(`${API_BASE}${path}`, { ...options, method, headers, credentials: "include" });
  if (response.status === 204) return null;
  const json = (response.headers.get("content-type") || "").includes("application/json");
  const body = json ? await response.json() : await response.text();
  if (!response.ok) {
    if (response.status === 401 && !["/api/auth/login", "/api/auth/me"].includes(path)) window.dispatchEvent(new Event("cloudshield:session-expired"));
    throw new Error(body?.message || body || `Request failed (${response.status})`);
  }
  return body;
}

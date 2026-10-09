import { useCallback, useEffect, useState } from "react";
import { api, refreshCsrf } from "./api";
import MonitoringDashboard from "./MonitoringDashboard";
import "./App.css";

function App() {
  const [user, setUser] = useState(null);
  const [health, setHealth] = useState("Checking backend…");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [users, setUsers] = useState([]);
  const [tab, setTab] = useState("overview");
  const [busy, setBusy] = useState(false);

  const loadSession = useCallback(async () => {
    try { setUser(await api("/api/auth/me")); } catch { setUser(null); }
  }, []);
  useEffect(() => {
    fetch(`${import.meta.env.VITE_API_BASE_URL || "http://localhost:8080"}/api/health`)
      .then((response) => response.ok ? response.text() : Promise.reject())
      .then(setHealth).catch(() => setHealth("Backend unavailable"));
    refreshCsrf().then(loadSession).catch(() => setUser(null));
    const expired = () => { setUser(null); setError("Your session expired. Please sign in again."); };
    window.addEventListener("cloudshield:session-expired", expired);
    return () => window.removeEventListener("cloudshield:session-expired", expired);
  }, [loadSession]);
  useEffect(() => {
    if (user?.role === "ADMIN" && tab === "users") api("/api/admin/users").then(setUsers).catch((e) => setError(e.message));
  }, [user, tab]);

  async function signIn(event) {
    event.preventDefault(); setError(""); setBusy(true);
    try { await api("/api/auth/login", { method: "POST", body: JSON.stringify({ username, password }) }); setPassword(""); await refreshCsrf(); await loadSession(); }
    catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  async function signOut() {
    setError("");
    try { await api("/api/auth/logout", { method: "POST" }); } catch (e) { setError(e.message); }
    finally { setUser(null); await refreshCsrf().catch(() => {}); }
  }
  async function createUser(event) {
    event.preventDefault(); setError(""); const form = new FormData(event.currentTarget);
    try { await api("/api/admin/users", { method: "POST", body: JSON.stringify(Object.fromEntries(form)) }); event.currentTarget.reset(); setUsers(await api("/api/admin/users")); }
    catch (e) { setError(e.message); }
  }

  if (!user) return <main className="auth-shell"><section className="login-card">
    <div className="brand-mark">CS</div><p className="eyebrow">CLOUD INFRASTRUCTURE</p><h1>Welcome back</h1><p className="muted">Sign in to your CloudShield workspace.</p>
    {error && <div className="notice error" role="alert">{error}</div>}
    <form onSubmit={signIn} className="stack">
      <label>Username<input autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required /></label>
      <label>Password<input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required /></label>
      <button disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
    </form><div className="backend-state"><span className={health.includes("running") ? "dot online" : "dot"} />{health}</div>
  </section></main>;

  return <div className="app-shell"><aside className="sidebar">
    <div className="brand"><span className="brand-mark">CS</span><strong>CloudShield</strong></div><p className="eyebrow nav-label">WORKSPACE</p>
    <nav aria-label="Main navigation">
      <button className={tab === "overview" ? "nav-item active" : "nav-item"} onClick={() => setTab("overview")}>◈ <span>Overview</span></button>
      {user.role === "ADMIN" && <button className={tab === "users" ? "nav-item active" : "nav-item"} onClick={() => setTab("users")}>♙ <span>User access</span></button>}
    </nav><div className="sidebar-foot"><span className="dot online" />{health}</div>
  </aside><main className="main-panel">
    <header className="topbar"><div><p className="eyebrow">CLOUDSHIELD / {tab === "users" ? "ACCESS" : "MONITORING"}</p><h1>{tab === "users" ? "User access" : "Monitoring"}</h1></div>
      <div className="profile"><div className="avatar">{user.displayName?.slice(0, 1).toUpperCase()}</div><div><strong>{user.displayName}</strong><span>{user.role}</span></div><button className="quiet-button" onClick={signOut}>Sign out</button></div>
    </header>
    {error && <div className="notice error" role="alert">{error}</div>}
    {tab === "overview" ? <MonitoringDashboard role={user.role} /> : <section className="users-layout">
      <article className="panel"><p className="eyebrow">DIRECTORY</p><h2>People and roles</h2><div className="user-list">{users.map((person) => <div className="user-row" key={person.id}>
        <div className="avatar small">{person.displayName.slice(0, 1).toUpperCase()}</div><div className="user-info"><strong>{person.displayName}</strong><span>{person.username} · {person.enabled ? "Enabled" : "Disabled"}</span></div><span className="role-pill">{person.role}</span>
      </div>)}</div></article>
      <article className="panel create-panel"><p className="eyebrow">NEW ACCOUNT</p><h2>Provision a user</h2><form className="stack" onSubmit={createUser}>
        <label>Username<input name="username" required maxLength="80" /></label><label>Display name<input name="displayName" required maxLength="120" /></label>
        <label>Temporary password<input name="password" type="password" minLength="14" maxLength="200" required /></label>
        <label>Role<select name="role" defaultValue="VIEWER"><option>VIEWER</option><option>DEVOPS</option><option>ADMIN</option></select></label><button>Create account</button>
      </form></article>
    </section>}
    <footer>CloudShield <span>·</span> Role-based workspace</footer>
  </main></div>;
}

export default App;

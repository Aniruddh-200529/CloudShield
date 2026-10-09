import { useCallback, useEffect, useState } from "react";
import { api } from "./api";

export default function MfaPanel() {
  const [status, setStatus] = useState(null);
  const [provisioningUri, setProvisioningUri] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const refresh = useCallback(async () => setStatus(await api("/api/auth/mfa/status")), []);
  useEffect(() => {
    let active = true;
    api("/api/auth/mfa/status").then((result) => { if (active) setStatus(result); }).catch((reason) => { if (active) setError(reason.message); });
    return () => { active = false; };
  }, []);

  async function beginEnrollment() {
    setBusy(true); setError(""); setMessage("");
    try { const result = await api("/api/auth/mfa/enrollment", { method: "POST" }); setProvisioningUri(result.provisioningUri); }
    catch (reason) { setError(reason.message); }
    finally { setBusy(false); }
  }
  async function confirmEnrollment(event) {
    event.preventDefault(); setBusy(true); setError(""); setMessage("");
    try { await api("/api/auth/mfa/confirm", { method: "POST", body: JSON.stringify({ code }) }); setProvisioningUri(""); setCode(""); setMessage("MFA is enabled."); await refresh(); }
    catch (reason) { setError(reason.message); }
    finally { setBusy(false); }
  }
  async function disable(event) {
    event.preventDefault(); setBusy(true); setError(""); setMessage("");
    try { await api("/api/auth/mfa/disable", { method: "POST", body: JSON.stringify({ password, code }) }); setPassword(""); setCode(""); setMessage("MFA is disabled."); await refresh(); }
    catch (reason) { setError(reason.message); }
    finally { setBusy(false); }
  }

  return <section className="panel security-panel"><p className="eyebrow">ACCOUNT SECURITY</p><h2>Multi-factor authentication</h2>
    {error && <div className="notice error" role="alert">{error}</div>}{message && <div className="notice success" role="status">{message}</div>}
    {status === null ? <p className="muted">Checking MFA status…</p> : <>
      <p className="muted">Status: <strong>{status.enabled ? "Enabled" : "Not enabled"}</strong>. TOTP codes expire every 30 seconds. Keep your authenticator device secure.</p>
      {!status.enabled && !provisioningUri && <button disabled={busy} onClick={beginEnrollment}>Set up authenticator</button>}
      {provisioningUri && <><p>In your authenticator app, add an account using this one-time provisioning URI. It contains the enrollment secret. Do not share or save it in a public place.</p>
        <textarea className="provisioning-uri" readOnly value={provisioningUri} aria-label="One-time authenticator provisioning URI" />
        <form className="inline-form" onSubmit={confirmEnrollment}><label>Authenticator code<input inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength="6" value={code} onChange={(event) => setCode(event.target.value)} required /></label><button disabled={busy || code.length !== 6}>Confirm MFA</button></form></>}
      {status.enabled && <form className="inline-form" onSubmit={disable}><p className="muted full-row">Disable only after confirming your current password and authenticator code.</p><label>Current password<input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required /></label><label>Authenticator code<input inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength="6" value={code} onChange={(event) => setCode(event.target.value)} required /></label><button className="danger-button" disabled={busy || code.length !== 6}>Disable MFA</button></form>}
    </>}
  </section>;
}

import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "./api";

const telemetryTypes = ["cpu.utilization", "memory.utilization", "disk.utilization", "network.rx.bytes", "network.tx.bytes"];
const formatMetric = (metric) => metric ? `${Number(metric.value).toLocaleString(undefined, { maximumFractionDigits: 2 })} ${metric.unit}` : "Unavailable";

export default function MonitoringDashboard({ role }) {
  const [resources, setResources] = useState([]); const [resourceId, setResourceId] = useState("");
  const [telemetry, setTelemetry] = useState({ resourceId: "", metrics: [], heartbeats: [] });
  const [now, setNow] = useState(0);
  const [refreshTick, setRefreshTick] = useState(0);
  const [alerts, setAlerts] = useState([]); const [alertStatus, setAlertStatus] = useState(""); const [alertPage, setAlertPage] = useState(0);
  const [selectedAlert, setSelectedAlert] = useState(null); const [alertHistory, setAlertHistory] = useState([]);
  const [error, setError] = useState(""); const [loading, setLoading] = useState(true);
  const canManageAlerts = role === "ADMIN" || role === "DEVOPS";

  const loadResources = useCallback(async () => {
    try { const rows = await api("/api/resources?size=200"); setResources(rows); if (rows.length) setResourceId(rows[0].id); }
    catch (e) { setError(e.message); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { const task = window.setTimeout(loadResources, 0); return () => window.clearTimeout(task); }, [loadResources]);
  const loadAlerts = useCallback(async () => {
    try { setAlerts(await api(`/api/alerts?limit=20&page=${alertPage}${alertStatus ? `&status=${alertStatus}` : ""}`)); setError(""); }
    catch (e) { setError(e.message); }
  }, [alertPage, alertStatus]);
  useEffect(() => { const task = window.setTimeout(loadAlerts, 0); return () => window.clearTimeout(task); }, [loadAlerts]);
  useEffect(() => {
    if (!resourceId) return;
    let active = true;
    Promise.all([api(`/api/resources/${resourceId}/metrics?limit=100`), api("/api/probe/heartbeats?limit=100")])
      .then(([metricRows, heartbeatRows]) => { if (active) { setTelemetry({ resourceId, metrics: metricRows, heartbeats: heartbeatRows.filter((row) => row.resourceId === resourceId) }); setNow(Date.now()); setError(""); } })
      .catch((e) => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [resourceId, refreshTick]);
  useEffect(() => { const timer = window.setInterval(() => { setNow(Date.now()); setRefreshTick((tick) => tick + 1); }, 30_000); return () => window.clearInterval(timer); }, []);
  useEffect(() => { const timer = window.setInterval(loadAlerts, 30_000); return () => window.clearInterval(timer); }, [loadAlerts]);
  const metrics = telemetry.resourceId === resourceId ? telemetry.metrics : [];
  const heartbeats = telemetry.resourceId === resourceId ? telemetry.heartbeats : [];
  const telemetryLoading = Boolean(resourceId) && telemetry.resourceId !== resourceId;
  const resource = resources.find((row) => row.id === resourceId);
  const latestByType = useMemo(() => Object.fromEntries(telemetryTypes.map((type) => [type, telemetry.resourceId === resourceId ? telemetry.metrics.find((metric) => metric.metricType === type) : undefined])), [telemetry, resourceId]);
  const metricIsStale = (metric) => Boolean(metric && now > 0 && now - Date.parse(metric.collectedAt) > 180_000);
  const heartbeat = heartbeats[0]; const heartbeatAge = heartbeat ? Math.max(0, now - Date.parse(heartbeat.receivedAt)) : null;
  const stale = heartbeatAge === null || (now > 0 && heartbeatAge > 180_000) || heartbeat.status !== "HEALTHY";
  const counts = useMemo(() => alerts.reduce((summary, item) => { summary[item.status] = (summary[item.status] || 0) + 1; summary[item.severity] = (summary[item.severity] || 0) + 1; return summary; }, {}), [alerts]);

  async function inspectAlert(alert) {
    setSelectedAlert(alert);
    try { setAlertHistory(await api(`/api/alerts/${alert.id}/history?limit=100`)); } catch (e) { setError(e.message); }
  }
  async function changeAlertStatus(status) {
    if (!selectedAlert) return;
    try { const updated = await api(`/api/alerts/${selectedAlert.id}/status`, { method: "PATCH", body: JSON.stringify({ status }) }); setSelectedAlert(updated); await Promise.all([loadAlerts(), inspectAlert(updated)]); }
    catch (e) { setError(e.message); }
  }

  return <section className="monitoring-layout">
    {error && <div className="notice error" role="alert">{error}</div>}
    <article className="panel monitoring-toolbar"><div><p className="eyebrow">LIVE TELEMETRY</p><h2>Resource monitoring</h2></div>
      <label>Monitored resource<select value={resourceId} onChange={(event) => setResourceId(event.target.value)} disabled={!resources.length}>
        {!resources.length && <option value="">No resources registered</option>}{resources.map((item) => <option key={item.id} value={item.id}>{item.name} · {item.resourceIdentifier}</option>)}
      </select></label>
    </article>
    {loading ? <article className="panel">Loading resources…</article> : !resource ? <article className="panel empty-state"><h3>No monitored resources</h3><p>Create a resource through the authorized resource API before starting a probe.</p></article> : <>
      <div className="telemetry-summary">
        <article className="panel resource-state"><p className="eyebrow">RESOURCE</p><strong>{resource.name}</strong><span>{resource.resourceIdentifier} · {resource.status}</span></article>
        <article className="panel resource-state"><p className="eyebrow">PROBE HEARTBEAT</p><strong className={stale ? "stale-text" : "healthy-text"}>{stale ? "STALE / UNHEALTHY" : "HEALTHY"}</strong><span>{heartbeat ? `${heartbeat.probeIdentifier} · ${new Date(heartbeat.receivedAt).toLocaleString()}` : "No heartbeat received for this resource"}</span></article>
      </div>
      <div className="metric-cards">
        {["cpu.utilization", "memory.utilization", "disk.utilization", "memory.available", "disk.available", "network.rx.bytes", "network.tx.bytes"].map((type) => <article className="panel metric-card" key={type}>
          <p>{type.replaceAll(".", " ")}</p><strong>{formatMetric(latestByType[type])}</strong><span className={metricIsStale(latestByType[type]) ? "stale-text" : ""}>{latestByType[type] ? metricIsStale(latestByType[type]) ? `Stale sample · ${new Date(latestByType[type].collectedAt).toLocaleString()}` : new Date(latestByType[type].collectedAt).toLocaleString() : "Unavailable · no sample received"}</span>
        </article>)}
      </div>
      <article className="panel data-panel"><div className="section-heading"><div><p className="eyebrow">OBSERVATIONS</p><h3>Recent metric history</h3></div><span>{telemetryLoading ? "Refreshing…" : `${metrics.length} samples`}</span></div>
        {metrics.length ? <div className="table-scroll"><table><thead><tr><th>Metric</th><th>Value</th><th>Collected (local time)</th></tr></thead><tbody>{metrics.slice(0, 20).map((sample) => <tr key={sample.id}><td>{sample.metricType}</td><td>{formatMetric(sample)}</td><td>{new Date(sample.collectedAt).toLocaleString()}</td></tr>)}</tbody></table></div> : <p className="empty-state">No telemetry has been recorded for this resource.</p>}
      </article>
    </>}
    <article className="panel data-panel"><div className="section-heading"><div><p className="eyebrow">ALERT ENGINE</p><h3>Alerts</h3></div>
      <label>State<select value={alertStatus} onChange={(event) => { setAlertStatus(event.target.value); setAlertPage(0); }}><option value="">All states</option><option>OPEN</option><option>ACKNOWLEDGED</option><option>RESOLVED</option></select></label></div>
      <div className="alert-counts"><span>On this page</span><span>Open {counts.OPEN || 0}</span><span>Acknowledged {counts.ACKNOWLEDGED || 0}</span><span>Resolved {counts.RESOLVED || 0}</span><span>Critical {counts.CRITICAL || 0}</span><span>High {counts.HIGH || 0}</span></div>
      {alerts.length ? <div className="alert-list">{alerts.map((alert) => <button className="alert-row" key={alert.id} onClick={() => inspectAlert(alert)}><span className={`severity-dot severity-${alert.severity.toLowerCase()}`} /><span><strong>{alert.alertType}</strong><small>{alert.description}</small></span><span className="role-pill">{alert.status}</span><time>{new Date(alert.createdAt).toLocaleString()}</time></button>)}</div> : <p className="empty-state">No alerts in this result set.</p>}
      <div className="pager"><button disabled={alertPage === 0} onClick={() => setAlertPage((value) => Math.max(0, value - 1))}>Previous</button><span>Page {alertPage + 1}</span><button disabled={alerts.length < 20} onClick={() => setAlertPage((value) => value + 1)}>Next</button></div>
    </article>
    {selectedAlert && <article className="panel data-panel alert-detail"><div className="section-heading"><div><p className="eyebrow">ALERT DETAIL</p><h3>{selectedAlert.alertType} · {selectedAlert.severity}</h3></div><button className="quiet-button" onClick={() => setSelectedAlert(null)}>Close</button></div>
      <p>{selectedAlert.description}</p><p className="muted">State: {selectedAlert.status} · Resource: {selectedAlert.resourceId || "Not associated"} · Updated {new Date(selectedAlert.updatedAt).toLocaleString()}</p>
      {canManageAlerts && selectedAlert.status !== "RESOLVED" && <div className="alert-actions">{selectedAlert.status !== "ACKNOWLEDGED" && <button onClick={() => changeAlertStatus("ACKNOWLEDGED")}>Acknowledge</button>}<button onClick={() => changeAlertStatus("RESOLVED")}>Resolve</button></div>}
      <h4>Status timeline</h4>{alertHistory.length ? <ol className="timeline">{alertHistory.map((entry) => <li key={entry.id}><strong>{entry.previousStatus || "Created"} → {entry.newStatus}</strong><span>{entry.actorIdentifier || "system"} · {new Date(entry.changedAt).toLocaleString()}</span></li>)}</ol> : <p className="muted">No status transitions recorded.</p>}
    </article>}
  </section>;
}

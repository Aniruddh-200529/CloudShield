import { useEffect, useState } from "react";
import { api } from "./api";

export default function AuditEvents() {
  const [events, setEvents] = useState([]); const [error, setError] = useState(""); const [loading, setLoading] = useState(true);
  useEffect(() => { let active = true; api("/api/audit-events?limit=100").then((rows) => { if (active) setEvents(rows); }).catch((reason) => { if (active) setError(reason.message); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, []);
  return <section className="panel data-panel"><div className="section-heading"><div><p className="eyebrow">SECURITY RECORD</p><h2>Recent audit events</h2></div><span>Latest 100</span></div>
    {error && <div className="notice error" role="alert">{error}</div>}{loading ? <p>Loading audit history…</p> : events.length ? <div className="table-scroll"><table><thead><tr><th>Time</th><th>Event</th><th>Actor</th><th>Target</th><th>Outcome</th></tr></thead><tbody>{events.map((event) => <tr key={event.id}><td>{new Date(event.occurredAt).toLocaleString()}</td><td>{event.eventType}</td><td>{event.actorIdentifier || "—"}</td><td>{event.targetIdentifier || "—"}</td><td>{event.outcome}</td></tr>)}</tbody></table></div> : <p className="empty-state">No audit events are available.</p>}
  </section>;
}

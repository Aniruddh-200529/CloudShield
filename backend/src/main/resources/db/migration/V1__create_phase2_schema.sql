CREATE TABLE monitored_resources (
    id UUID PRIMARY KEY,
    resource_identifier VARCHAR(160) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    resource_type VARCHAR(48) NOT NULL,
    address VARCHAR(512),
    environment VARCHAR(80),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_monitored_resources_type_status ON monitored_resources (resource_type, status);

CREATE TABLE metric_samples (
    id UUID PRIMARY KEY,
    resource_id UUID NOT NULL REFERENCES monitored_resources(id) ON DELETE RESTRICT,
    metric_type VARCHAR(80) NOT NULL,
    metric_value DOUBLE PRECISION NOT NULL,
    unit VARCHAR(32),
    collected_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_metric_samples_resource_time ON metric_samples (resource_id, collected_at DESC);
CREATE INDEX idx_metric_samples_type_time ON metric_samples (metric_type, collected_at DESC);

CREATE TABLE probe_heartbeats (
    id UUID PRIMARY KEY,
    probe_identifier VARCHAR(160) NOT NULL,
    resource_id UUID REFERENCES monitored_resources(id) ON DELETE RESTRICT,
    status VARCHAR(32) NOT NULL,
    health_message VARCHAR(500),
    received_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_probe_heartbeats_probe_time ON probe_heartbeats (probe_identifier, received_at DESC);
CREATE INDEX idx_probe_heartbeats_resource_time ON probe_heartbeats (resource_id, received_at DESC);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    actor_identifier VARCHAR(160),
    target_identifier VARCHAR(160),
    resource_id UUID REFERENCES monitored_resources(id) ON DELETE RESTRICT,
    occurred_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    details TEXT
);

CREATE INDEX idx_audit_events_time ON audit_events (occurred_at DESC);
CREATE INDEX idx_audit_events_type_time ON audit_events (event_type, occurred_at DESC);

CREATE TABLE alert_records (
    id UUID PRIMARY KEY,
    alert_type VARCHAR(100) NOT NULL,
    severity VARCHAR(24) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    resource_id UUID REFERENCES monitored_resources(id) ON DELETE RESTRICT,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_alert_records_status_created ON alert_records (status, created_at DESC);
CREATE INDEX idx_alert_records_resource_created ON alert_records (resource_id, created_at DESC);

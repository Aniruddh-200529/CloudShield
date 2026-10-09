ALTER TABLE metric_samples
    ADD COLUMN probe_identifier VARCHAR(160),
    ADD COLUMN observation_id UUID;

CREATE UNIQUE INDEX uk_metric_probe_observation
    ON metric_samples (probe_identifier, observation_id)
    WHERE probe_identifier IS NOT NULL AND observation_id IS NOT NULL;

ALTER TABLE alert_records ADD COLUMN deduplication_key VARCHAR(240);
CREATE UNIQUE INDEX uk_active_alert_deduplication
    ON alert_records (deduplication_key)
    WHERE deduplication_key IS NOT NULL AND status IN ('OPEN', 'ACKNOWLEDGED');

CREATE TABLE alert_status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_id UUID NOT NULL REFERENCES alert_records(id) ON DELETE RESTRICT,
    previous_status VARCHAR(24),
    new_status VARCHAR(24) NOT NULL,
    actor_identifier VARCHAR(160),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_alert_status_history_alert_time ON alert_status_history(alert_id, changed_at DESC);

CREATE INDEX idx_heartbeat_resource_received ON probe_heartbeats(resource_id, received_at DESC);

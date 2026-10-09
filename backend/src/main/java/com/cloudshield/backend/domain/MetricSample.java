package com.cloudshield.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "metric_samples")
public class MetricSample {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "resource_id", nullable = false) private MonitoredResource resource;
    @Column(name = "metric_type", nullable = false, length = 80) private String metricType;
    @Column(name = "metric_value", nullable = false) private double value;
    @Column(length = 32) private String unit;
    @Column(name = "collected_at", nullable = false) private Instant collectedAt;
    protected MetricSample() {}
    public MetricSample(MonitoredResource resource, String metricType, double value, String unit, Instant collectedAt) { this.resource = resource; this.metricType = metricType; this.value = value; this.unit = unit; this.collectedAt = collectedAt; }
    public UUID getId() { return id; }
    public MonitoredResource getResource() { return resource; }
    public String getMetricType() { return metricType; }
    public double getValue() { return value; }
    public String getUnit() { return unit; }
    public Instant getCollectedAt() { return collectedAt; }
}

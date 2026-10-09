package com.cloudshield.probe;

import java.time.Instant;
import java.util.UUID;

public record MetricObservation(UUID observationId, String metricType, double value, String unit, Instant collectedAt) {}

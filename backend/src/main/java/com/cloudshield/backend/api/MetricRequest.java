package com.cloudshield.backend.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record MetricRequest(@NotBlank @Size(max = 80) String metricType, @NotNull Double value, @Size(max = 32) String unit, Instant collectedAt) {}

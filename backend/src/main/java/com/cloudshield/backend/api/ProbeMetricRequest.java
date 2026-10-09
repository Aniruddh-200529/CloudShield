package com.cloudshield.backend.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProbeMetricRequest(
        @NotBlank @Size(max = 160) String probeIdentifier,
        @NotBlank @Size(max = 160) String resourceIdentifier,
        @NotNull @Size(min = 1, max = 32) List<@Valid Observation> observations) {
    public record Observation(@NotNull UUID observationId, @NotBlank @Size(max = 80) String metricType,
            @NotNull Double value, @NotBlank @Size(max = 32) String unit, @NotNull Instant collectedAt) {}
}

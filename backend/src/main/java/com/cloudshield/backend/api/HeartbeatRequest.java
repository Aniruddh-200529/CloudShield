package com.cloudshield.backend.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record HeartbeatRequest(@NotBlank @Size(max = 160) String probeIdentifier, UUID resourceId, @NotBlank @Pattern(regexp = "HEALTHY|DEGRADED|UNHEALTHY") String status, @Size(max = 500) String healthMessage, Instant receivedAt) {}

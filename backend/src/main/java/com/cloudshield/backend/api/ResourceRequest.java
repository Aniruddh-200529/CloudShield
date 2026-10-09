package com.cloudshield.backend.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResourceRequest(
        @NotBlank @Size(max = 160) String resourceIdentifier,
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 48) String resourceType,
        @Size(max = 512) String address,
        @Size(max = 80) String environment,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE|UNKNOWN|DEGRADED") String status) {}

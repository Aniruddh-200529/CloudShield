package com.cloudshield.backend.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AlertRequest(@NotBlank @Size(max = 100) String alertType, @NotBlank @Pattern(regexp = "INFO|LOW|MEDIUM|HIGH|CRITICAL") String severity, @NotBlank @Size(max = 2000) String description, UUID resourceId) {}

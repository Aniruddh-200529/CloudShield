package com.cloudshield.backend.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AlertStatusRequest(@NotBlank @Pattern(regexp = "OPEN|ACKNOWLEDGED|RESOLVED") String status) {}

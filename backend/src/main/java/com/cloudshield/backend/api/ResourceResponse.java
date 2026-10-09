package com.cloudshield.backend.api;

import com.cloudshield.backend.domain.MonitoredResource;
import java.time.Instant;
import java.util.UUID;

public record ResourceResponse(UUID id, String resourceIdentifier, String name, String resourceType, String address, String environment, String status, Instant createdAt, Instant updatedAt) {
    public static ResourceResponse from(MonitoredResource r) { return new ResourceResponse(r.getId(), r.getResourceIdentifier(), r.getName(), r.getResourceType(), r.getAddress(), r.getEnvironment(), r.getStatus(), r.getCreatedAt(), r.getUpdatedAt()); }
}

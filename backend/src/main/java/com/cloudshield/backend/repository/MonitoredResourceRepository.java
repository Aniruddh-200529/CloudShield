package com.cloudshield.backend.repository;

import com.cloudshield.backend.domain.MonitoredResource;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoredResourceRepository extends JpaRepository<MonitoredResource, UUID> {
    boolean existsByResourceIdentifier(String resourceIdentifier);
}

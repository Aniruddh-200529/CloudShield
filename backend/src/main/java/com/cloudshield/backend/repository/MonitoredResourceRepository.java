package com.cloudshield.backend.repository;

import com.cloudshield.backend.domain.MonitoredResource;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface MonitoredResourceRepository extends JpaRepository<MonitoredResource, UUID> {
    boolean existsByResourceIdentifier(String resourceIdentifier);
    java.util.Optional<MonitoredResource> findByResourceIdentifier(String resourceIdentifier);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MonitoredResource r where r.resourceIdentifier = :identifier")
    java.util.Optional<MonitoredResource> lockByIdentifier(@Param("identifier") String identifier);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MonitoredResource r where r.id = :id")
    java.util.Optional<MonitoredResource> lockById(@Param("id") UUID id);
}

package com.cloudshield.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "monitored_resources", uniqueConstraints = @UniqueConstraint(name = "uk_monitored_resources_identifier", columnNames = "resource_identifier"))
public class MonitoredResource {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "resource_identifier", nullable = false, length = 160)
    private String resourceIdentifier;
    @Column(nullable = false, length = 160)
    private String name;
    @Column(name = "resource_type", nullable = false, length = 48)
    private String resourceType;
    @Column(length = 512)
    private String address;
    @Column(length = 80)
    private String environment;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MonitoredResource() {}
    public MonitoredResource(String resourceIdentifier, String name, String resourceType, String address, String environment, String status) {
        this.resourceIdentifier = resourceIdentifier; this.name = name; this.resourceType = resourceType;
        this.address = address; this.environment = environment; this.status = status;
    }
    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getResourceIdentifier() { return resourceIdentifier; }
    public String getName() { return name; }
    public String getResourceType() { return resourceType; }
    public String getAddress() { return address; }
    public String getEnvironment() { return environment; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void update(String name, String resourceType, String address, String environment, String status) {
        this.name = name; this.resourceType = resourceType; this.address = address; this.environment = environment; this.status = status;
    }
}

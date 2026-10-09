package com.cloudshield.backend.service;

import com.cloudshield.backend.api.ResourceRequest;
import com.cloudshield.backend.domain.MonitoredResource;
import com.cloudshield.backend.repository.MonitoredResourceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceService {
    private final MonitoredResourceRepository repository;
    public ResourceService(MonitoredResourceRepository repository) { this.repository = repository; }
    @Transactional(readOnly = true) public List<MonitoredResource> list(int page, int size) { return repository.findAll(PageRequest.of(page, size)).getContent(); }
    @Transactional(readOnly = true) public MonitoredResource get(UUID id) { return repository.findById(id).orElseThrow(() -> new NotFoundException("Resource not found")); }
    @Transactional(readOnly = true) public MonitoredResource findByIdentifier(String identifier) { return repository.findByResourceIdentifier(identifier).orElseThrow(() -> new NotFoundException("Resource not found")); }
    @Transactional public MonitoredResource lock(UUID id) { return repository.lockById(id).orElseThrow(() -> new NotFoundException("Resource not found")); }
    @Transactional public MonitoredResource lockByIdentifier(String identifier) { return repository.lockByIdentifier(identifier).orElseThrow(() -> new NotFoundException("Resource not found")); }
    @Transactional public MonitoredResource create(ResourceRequest request) {
        if (repository.existsByResourceIdentifier(request.resourceIdentifier())) throw new ConflictException("Resource identifier already exists");
        return repository.save(new MonitoredResource(request.resourceIdentifier(), request.name(), request.resourceType(), request.address(), request.environment(), request.status()));
    }
    @Transactional public MonitoredResource update(UUID id, ResourceRequest request) {
        MonitoredResource resource = get(id);
        if (!resource.getResourceIdentifier().equals(request.resourceIdentifier()) && repository.existsByResourceIdentifier(request.resourceIdentifier())) throw new ConflictException("Resource identifier already exists");
        if (!resource.getResourceIdentifier().equals(request.resourceIdentifier())) throw new IllegalArgumentException("Resource identifier cannot be changed");
        resource.update(request.name(), request.resourceType(), request.address(), request.environment(), request.status());
        return resource;
    }
    @Transactional public void delete(UUID id) { repository.delete(get(id)); }
}

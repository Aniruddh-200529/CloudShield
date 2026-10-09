package com.cloudshield.backend.service;

import com.cloudshield.backend.api.AlertRequest;
import com.cloudshield.backend.domain.AlertRecord;
import com.cloudshield.backend.repository.AlertRecordRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertService {
    private final AlertRecordRepository repository;
    private final ResourceService resources;
    public AlertService(AlertRecordRepository repository, ResourceService resources) { this.repository = repository; this.resources = resources; }
    @Transactional public AlertRecord create(AlertRequest request) { return repository.save(new AlertRecord(request.alertType(), request.severity(), request.description(), request.resourceId() == null ? null : resources.get(request.resourceId()), "OPEN")); }
    @Transactional(readOnly = true) public List<AlertRecord> list(String status, int limit) { return status == null ? repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)) : repository.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(0, limit)); }
    @Transactional public AlertRecord updateStatus(UUID id, String status) { AlertRecord alert = repository.findById(id).orElseThrow(() -> new NotFoundException("Alert not found")); alert.changeStatus(status); return alert; }
}

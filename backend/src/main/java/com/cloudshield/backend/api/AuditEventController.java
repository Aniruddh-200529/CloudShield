package com.cloudshield.backend.api;

import com.cloudshield.backend.service.AuditEventService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/audit-events") @Validated
public class AuditEventController {
    private final AuditEventService service;
    public AuditEventController(AuditEventService service) { this.service = service; }
    @PostMapping public ResponseEntity<AuditEventResponse> record(@Valid @RequestBody AuditEventRequest request) { return ResponseEntity.status(201).body(AuditEventResponse.from(service.record(request))); }
    @GetMapping public List<AuditEventResponse> list(@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) { return service.list(limit).stream().map(AuditEventResponse::from).toList(); }
}

package com.cloudshield.backend.api;

import com.cloudshield.backend.service.MetricService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/resources/{resourceId}/metrics") @Validated
public class MetricController {
    private final MetricService service;
    public MetricController(MetricService service) { this.service = service; }
    @PostMapping public ResponseEntity<MetricResponse> record(@PathVariable UUID resourceId, @Valid @RequestBody MetricRequest request) { return ResponseEntity.status(201).body(MetricResponse.from(service.record(resourceId, request))); }
    @GetMapping public List<MetricResponse> history(@PathVariable UUID resourceId, @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) { return service.history(resourceId, from, to, limit).stream().map(MetricResponse::from).toList(); }
}

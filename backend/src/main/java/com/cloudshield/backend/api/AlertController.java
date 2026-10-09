package com.cloudshield.backend.api;

import com.cloudshield.backend.service.AlertService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

@RestController @RequestMapping("/api/alerts") @Validated
public class AlertController {
    private final AlertService service;
    public AlertController(AlertService service) { this.service = service; }
    @PostMapping public ResponseEntity<AlertResponse> create(@Valid @RequestBody AlertRequest request, Authentication actor) { return ResponseEntity.status(201).body(AlertResponse.from(service.create(request, actor.getName()))); }
    @GetMapping public List<AlertResponse> list(@RequestParam(required = false) @Pattern(regexp = "OPEN|ACKNOWLEDGED|RESOLVED") String status, @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page, @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) { return service.list(status, page, limit).stream().map(AlertResponse::from).toList(); }
    @GetMapping("/{id}") public AlertResponse get(@PathVariable UUID id) { return AlertResponse.from(service.get(id)); }
    @GetMapping("/{id}/history") public List<AlertHistoryResponse> history(@PathVariable UUID id, @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) { return service.history(id, limit).stream().map(AlertHistoryResponse::from).toList(); }
    @PatchMapping("/{id}/status") public AlertResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody AlertStatusRequest request, Authentication actor) { return AlertResponse.from(service.updateStatus(id, request.status(), actor.getName())); }
}

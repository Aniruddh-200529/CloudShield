package com.cloudshield.backend.api;

import com.cloudshield.backend.service.ProbeTelemetryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/probe")
public class ProbeMetricsController {
    private final ProbeTelemetryService telemetry;
    public ProbeMetricsController(ProbeTelemetryService telemetry) { this.telemetry = telemetry; }
    @PostMapping("/metrics") public ResponseEntity<ProbeMetricResponse> receive(@Valid @RequestBody ProbeMetricRequest request) {
        return ResponseEntity.ok(telemetry.ingest(request));
    }
}

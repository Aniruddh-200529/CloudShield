package com.cloudshield.backend;

import com.cloudshield.backend.api.HeartbeatRequest;
import com.cloudshield.backend.api.HeartbeatResponse;
import com.cloudshield.backend.service.HeartbeatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/probe")
@Validated
public class ProbeController {

    private final HeartbeatService heartbeatService;

    public ProbeController(HeartbeatService heartbeatService) { this.heartbeatService = heartbeatService; }

    @GetMapping("/heartbeat")
    public String heartbeat() {
        return "CloudShield probe connection successful";
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<HeartbeatResponse> receive(@Valid @RequestBody HeartbeatRequest request) {
        return ResponseEntity.status(201).body(HeartbeatResponse.from(heartbeatService.record(request)));
    }

    @GetMapping("/heartbeats")
    public List<HeartbeatResponse> history(@RequestParam(required = false) String probeIdentifier,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return heartbeatService.list(probeIdentifier, limit).stream().map(HeartbeatResponse::from).toList();
    }
}

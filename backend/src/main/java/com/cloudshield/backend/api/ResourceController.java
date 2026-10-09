package com.cloudshield.backend.api;

import com.cloudshield.backend.service.ResourceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/resources") @Validated
public class ResourceController {
    private final ResourceService service;
    public ResourceController(ResourceService service) { this.service = service; }
    @GetMapping public List<ResourceResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size) { return service.list(page, size).stream().map(ResourceResponse::from).toList(); }
    @PostMapping public ResponseEntity<ResourceResponse> create(@Valid @RequestBody ResourceRequest request) { var result = ResourceResponse.from(service.create(request)); return ResponseEntity.created(URI.create("/api/resources/" + result.id())).body(result); }
    @GetMapping("/{id}") public ResourceResponse get(@PathVariable UUID id) { return ResourceResponse.from(service.get(id)); }
    @PutMapping("/{id}") public ResourceResponse update(@PathVariable UUID id, @Valid @RequestBody ResourceRequest request) { return ResourceResponse.from(service.update(id, request)); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id) { service.delete(id); return ResponseEntity.noContent().build(); }
}

package com.cloudshield.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudshield.backend.api.MetricRequest;
import com.cloudshield.backend.api.ResourceRequest;
import com.cloudshield.backend.api.AuditEventRequest;
import com.cloudshield.backend.domain.MonitoredResource;
import com.cloudshield.backend.domain.AlertRecord;
import com.cloudshield.backend.repository.MetricSampleRepository;
import com.cloudshield.backend.repository.AuditEventRepository;
import com.cloudshield.backend.repository.MonitoredResourceRepository;
import com.cloudshield.backend.service.ConflictException;
import com.cloudshield.backend.service.MetricService;
import com.cloudshield.backend.service.AuditEventService;
import com.cloudshield.backend.service.NotFoundException;
import com.cloudshield.backend.service.ResourceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class PhaseTwoServiceTests {
    private final MonitoredResourceRepository resources = mock(MonitoredResourceRepository.class);

    @Test
    void createsResourcesAndRejectsDuplicateIdentifiers() {
        ResourceService service = new ResourceService(resources);
        ResourceRequest request = new ResourceRequest("vm-1", "VM one", "VM", null, "dev", "ACTIVE");
        when(resources.existsByResourceIdentifier("vm-1")).thenReturn(false);
        when(resources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.create(request).getResourceIdentifier()).isEqualTo("vm-1");
        verify(resources).save(any(MonitoredResource.class));
        when(resources.existsByResourceIdentifier("vm-1")).thenReturn(true);
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(ConflictException.class);
    }

    @Test
    void reportsMissingResourcesAndBoundsMetricHistoryQuery() {
        UUID resourceId = UUID.randomUUID();
        when(resources.findById(resourceId)).thenReturn(Optional.empty());
        ResourceService resourceService = new ResourceService(resources);
        assertThatThrownBy(() -> resourceService.get(resourceId)).isInstanceOf(NotFoundException.class);
        MetricSampleRepository metrics = mock(MetricSampleRepository.class);
        MetricService metricService = new MetricService(metrics, resourceService);
        assertThatThrownBy(() -> metricService.record(resourceId, new MetricRequest("cpu", 2.0, "percent", null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> metricService.history(resourceId, Instant.parse("2026-02-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), 100)).isInstanceOf(NotFoundException.class);
        verify(metrics, org.mockito.Mockito.never()).findHistory(eq(resourceId), any(), any(), any());
    }

    @Test
    void metricHistoryPassesFiltersAndPageBoundToRepository() {
        UUID resourceId = UUID.randomUUID();
        MonitoredResource resource = new MonitoredResource("vm-2", "VM two", "VM", null, null, "ACTIVE");
        when(resources.findById(resourceId)).thenReturn(Optional.of(resource));
        MetricSampleRepository metrics = mock(MetricSampleRepository.class);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");
        when(metrics.findHistory(eq(resourceId), eq(from), eq(to), any(PageRequest.class))).thenReturn(List.of());
        MetricService service = new MetricService(metrics, new ResourceService(resources));
        assertThat(service.history(resourceId, from, to, 50)).isEmpty();
        verify(metrics).findHistory(eq(resourceId), eq(from), eq(to), eq(PageRequest.of(0, 50)));
        assertThatThrownBy(() -> service.history(resourceId, to, from, 50)).isInstanceOf(IllegalArgumentException.class);
        verify(metrics, org.mockito.Mockito.times(1)).findHistory(eq(resourceId), any(), any(), any());
    }

    @Test
    void validatesResourceRequestsAndRecordsAlertLifecycleTimes() {
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            ResourceRequest invalid = new ResourceRequest("", "", "VM", null, null, "NOT_A_STATUS");
            assertThat(validatorFactory.getValidator().validate(invalid)).hasSizeGreaterThanOrEqualTo(3);
        }
        AlertRecord alert = new AlertRecord("CAPACITY", "HIGH", "Threshold exceeded", null, "OPEN");
        alert.changeStatus("ACKNOWLEDGED");
        assertThat(alert.getAcknowledgedAt()).isNotNull();
        alert.changeStatus("RESOLVED");
        assertThat(alert.getResolvedAt()).isNotNull();
        assertThatThrownBy(() -> alert.changeStatus("ACKNOWLEDGED")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsPhaseOneHealthResponse() {
        assertThat(new HealthController().health()).isEqualTo("CloudShield backend is running");
    }

    @Test
    void rejectsSensitiveFieldsInNestedCustomAuditDetailsBeforePersistence() {
        AuditEventRepository events = mock(AuditEventRepository.class);
        AuditEventService service = new AuditEventService(events, new ResourceService(resources));
        AuditEventRequest request = new AuditEventRequest("OPERATOR_EVENT", "operator", null, null, null, "SUCCESS",
                Map.of("context", Map.of("api-token", "test-value")));

        assertThatThrownBy(() -> service.record(request)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Audit details cannot contain sensitive fields");
        verify(events, org.mockito.Mockito.never()).save(any());
    }
}

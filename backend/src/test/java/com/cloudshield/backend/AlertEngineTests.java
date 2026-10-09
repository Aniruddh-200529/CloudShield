package com.cloudshield.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudshield.backend.domain.AlertRecord;
import com.cloudshield.backend.domain.MetricSample;
import com.cloudshield.backend.domain.MonitoredResource;
import com.cloudshield.backend.repository.AlertRecordRepository;
import com.cloudshield.backend.repository.AlertStatusHistoryRepository;
import com.cloudshield.backend.repository.ProbeHeartbeatRepository;
import com.cloudshield.backend.service.AlertService;
import com.cloudshield.backend.service.AuditEventService;
import com.cloudshield.backend.service.ResourceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AlertEngineTests {
    @Test void staleHeartbeatAlertIsDeduplicatedAndRecovered() {
        AlertRecordRepository alerts = mock(AlertRecordRepository.class);
        AlertStatusHistoryRepository history = mock(AlertStatusHistoryRepository.class);
        AuditEventService audit = mock(AuditEventService.class);
        ResourceService resources = mock(ResourceService.class);
        ProbeHeartbeatRepository heartbeats = mock(ProbeHeartbeatRepository.class);
        MonitoredResource resource = new MonitoredResource("host", "Host", "HOST", null, null, "ACTIVE");
        UUID resourceId = UUID.randomUUID(); ReflectionTestUtils.setField(resource, "id", resourceId);
        when(resources.lock(resourceId)).thenReturn(resource);
        when(heartbeats.findFirstByResource_IdOrderByReceivedAtDesc(resourceId)).thenReturn(Optional.empty());
        var active = new AtomicReference<AlertRecord>();
        when(alerts.findFirstByDeduplicationKeyAndStatusIn(anyString(), anyCollection())).thenAnswer(call -> Optional.ofNullable(active.get()));
        when(alerts.save(any())).thenAnswer(call -> {
            AlertRecord record = call.getArgument(0); ReflectionTestUtils.setField(record, "id", UUID.randomUUID()); active.set(record); return record;
        });
        var service = new AlertService(alerts, resources, history, audit, heartbeats, 180);
        service.evaluateProbeStale(resource);
        service.evaluateProbeStale(resource);
        assertThat(active.get()).isNotNull().extracting(AlertRecord::getAlertType).isEqualTo("PROBE_STALE");
        verify(alerts, times(1)).save(any(AlertRecord.class));
        service.resolveProbeStale(resource);
        assertThat(active.get().getStatus()).isEqualTo("RESOLVED");
        verify(history, times(2)).save(any());
        verify(audit).record(argThat(event -> "ALERT_AUTO_RESOLVED".equals(event.eventType())));
    }

    @Test void deduplicatesAnActiveThresholdIncidentAndResolvesItAfterRecovery() {
        AlertRecordRepository alerts = mock(AlertRecordRepository.class);
        AlertStatusHistoryRepository history = mock(AlertStatusHistoryRepository.class);
        AuditEventService audit = mock(AuditEventService.class);
        var active = new AtomicReference<AlertRecord>();
        when(alerts.findFirstByDeduplicationKeyAndStatusIn(anyString(), anyCollection())).thenAnswer(call -> Optional.ofNullable(active.get()));
        when(alerts.save(any())).thenAnswer(call -> {
            AlertRecord record = call.getArgument(0); ReflectionTestUtils.setField(record, "id", UUID.randomUUID()); active.set(record); return record;
        });
        var service = new AlertService(alerts, mock(ResourceService.class), history, audit, mock(ProbeHeartbeatRepository.class), 180);
        MonitoredResource resource = new MonitoredResource("host", "Host", "HOST", null, null, "ACTIVE");
        ReflectionTestUtils.setField(resource, "id", UUID.randomUUID());
        Instant now = Instant.now();
        var high = List.of(new MetricSample(resource, "cpu.utilization", 91, "%", now), new MetricSample(resource, "cpu.utilization", 92, "%", now.minusSeconds(1)));
        service.evaluateMetric(resource, "cpu.utilization", high, 85, "HIGH", 2);
        service.evaluateMetric(resource, "cpu.utilization", high, 85, "HIGH", 2);
        assertThat(active.get()).isNotNull().extracting(AlertRecord::getStatus).isEqualTo("OPEN");
        verify(alerts, times(1)).save(any(AlertRecord.class));
        var recovered = List.of(new MetricSample(resource, "cpu.utilization", 35, "%", Instant.now()));
        service.evaluateMetric(resource, "cpu.utilization", recovered, 85, "HIGH", 2);
        assertThat(active.get().getStatus()).isEqualTo("RESOLVED");
        verify(history, times(2)).save(any());
        verify(audit).record(argThat(event -> "ALERT_AUTO_RESOLVED".equals(event.eventType())));
    }

    @Test void alertStateHistoryAndAuditAreWrittenOnlyForActualStateChanges() {
        AlertRecordRepository alerts = mock(AlertRecordRepository.class);
        AlertStatusHistoryRepository history = mock(AlertStatusHistoryRepository.class);
        AuditEventService audit = mock(AuditEventService.class);
        AlertRecord record = new AlertRecord("CAPACITY", "HIGH", "High utilization", null, "OPEN");
        ReflectionTestUtils.setField(record, "id", UUID.randomUUID());
        when(alerts.findById(record.getId())).thenReturn(Optional.of(record));
        var service = new AlertService(alerts, mock(ResourceService.class), history, audit, mock(ProbeHeartbeatRepository.class), 180);
        service.updateStatus(record.getId(), "ACKNOWLEDGED", "ops");
        service.updateStatus(record.getId(), "ACKNOWLEDGED", "ops");
        assertThat(record.getAcknowledgedAt()).isNotNull();
        verify(history, times(1)).save(any()); verify(audit, times(1)).record(any());
    }
}

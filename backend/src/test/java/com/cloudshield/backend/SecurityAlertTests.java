package com.cloudshield.backend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cloudshield.backend.domain.AuditEvent;
import com.cloudshield.backend.repository.AuditEventRepository;
import com.cloudshield.backend.service.AlertService;
import com.cloudshield.backend.service.SecurityAuditService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityAlertTests {
    @Test void repeatedFailedLoginsCreateOneSuspiciousActivityIndicatorPerSourceWindow() {
        AuditEventRepository events = mock(AuditEventRepository.class);
        AlertService alerts = mock(AlertService.class);
        SecurityAuditService audit = new SecurityAuditService(events, alerts, 10);
        for (int attempt = 0; attempt < 20; attempt++) audit.record("LOGIN_FAILURE", "user", "user", "FAILURE", Map.of("source", "source-test"));
        verify(events, times(20)).save(any(AuditEvent.class));
        verify(alerts, times(1)).evaluateAuthenticationFailureBurst("source-test");
        verifyNoMoreInteractions(alerts);
    }
}

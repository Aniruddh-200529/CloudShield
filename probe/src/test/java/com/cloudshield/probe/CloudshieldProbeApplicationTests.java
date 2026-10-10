package com.cloudshield.probe;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(properties = {
        "cloudshield.probe.heartbeat.enabled=false",
        "PROBE_API_KEY=test-only-probe-key",
        "CLOUDSHIELD_BACKEND_URL=http://configured-backend.test:8080",
        "CLOUDSHIELD_PROBE_IDENTIFIER=configured-probe",
        "CLOUDSHIELD_RESOURCE_IDENTIFIER=configured-resource",
        "CLOUDSHIELD_PROBE_COLLECTION_INTERVAL_MS=5000"
})
@TestPropertySource(locations = "file:src/main/resources/application.properties")
class CloudshieldProbeApplicationTests {

	@Autowired
	private ProbeSettings settings;

	@Test
	void contextLoadsAndMapsDemoEnvironmentVariables() {
		assertThat(settings.backendUrl()).isEqualTo("http://configured-backend.test:8080");
		assertThat(settings.probeIdentifier()).isEqualTo("configured-probe");
		assertThat(settings.resourceIdentifier()).isEqualTo("configured-resource");
		assertThat(settings.apiKey()).isEqualTo("test-only-probe-key");
		assertThat(settings.intervalMs()).isEqualTo(5000);
	}

}

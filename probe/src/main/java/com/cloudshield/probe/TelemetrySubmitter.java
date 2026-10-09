package com.cloudshield.probe;

import java.util.List;

public interface TelemetrySubmitter {
    void heartbeat(String probeIdentifier, String resourceIdentifier, String status, String message);
    void metrics(String probeIdentifier, String resourceIdentifier, List<MetricObservation> observations);
}

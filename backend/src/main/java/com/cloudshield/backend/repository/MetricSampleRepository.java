package com.cloudshield.backend.repository;

import com.cloudshield.backend.domain.MetricSample;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MetricSampleRepository extends JpaRepository<MetricSample, UUID> {
    java.util.List<MetricSample> findByResource_IdOrderByCollectedAtDesc(UUID resourceId, Pageable pageable);
    java.util.List<MetricSample> findByResource_IdAndCollectedAtGreaterThanEqualOrderByCollectedAtDesc(UUID resourceId, Instant fromTime, Pageable pageable);
    java.util.List<MetricSample> findByResource_IdAndCollectedAtLessThanEqualOrderByCollectedAtDesc(UUID resourceId, Instant toTime, Pageable pageable);
    java.util.List<MetricSample> findByResource_IdAndCollectedAtBetweenOrderByCollectedAtDesc(UUID resourceId, Instant fromTime, Instant toTime, Pageable pageable);
    boolean existsByProbeIdentifierAndObservationId(String probeIdentifier, UUID observationId);
    java.util.Optional<MetricSample> findFirstByResource_IdAndMetricTypeOrderByCollectedAtDesc(java.util.UUID resourceId, String metricType);
    @Query("select m from MetricSample m where m.resource.id = :resourceId and m.metricType = :metricType and m.collectedAt >= :since order by m.collectedAt desc")
    java.util.List<MetricSample> recentForEvaluation(@Param("resourceId") UUID resourceId, @Param("metricType") String metricType, @Param("since") Instant since, Pageable pageable);
}

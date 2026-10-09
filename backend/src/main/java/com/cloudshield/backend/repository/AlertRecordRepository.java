package com.cloudshield.backend.repository;

import com.cloudshield.backend.domain.AlertRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRecordRepository extends JpaRepository<AlertRecord, UUID> {
    List<AlertRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<AlertRecord> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    java.util.Optional<AlertRecord> findFirstByDeduplicationKeyAndStatusIn(String key, java.util.Collection<String> statuses);
    List<AlertRecord> findByResource_IdOrderByCreatedAtDesc(UUID resourceId, Pageable pageable);
}

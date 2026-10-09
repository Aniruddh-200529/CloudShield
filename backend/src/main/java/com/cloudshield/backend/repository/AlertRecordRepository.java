package com.cloudshield.backend.repository;

import com.cloudshield.backend.domain.AlertRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRecordRepository extends JpaRepository<AlertRecord, UUID> {
    List<AlertRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<AlertRecord> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
}

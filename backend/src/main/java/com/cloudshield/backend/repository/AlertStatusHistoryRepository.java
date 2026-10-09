package com.cloudshield.backend.repository;

import com.cloudshield.backend.domain.AlertStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertStatusHistoryRepository extends JpaRepository<AlertStatusHistory, UUID> {
    List<AlertStatusHistory> findByAlert_IdOrderByChangedAtDesc(UUID alertId, Pageable pageable);
}

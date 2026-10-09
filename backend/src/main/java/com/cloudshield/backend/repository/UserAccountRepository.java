package com.cloudshield.backend.repository;

import com.cloudshield.backend.domain.UserAccount;
import com.cloudshield.backend.domain.UserRole;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByUsername(String username);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.username = :username")
    Optional<UserAccount> findByUsernameForUpdate(@Param("username") String username);
    long countByRoleAndEnabledTrue(UserRole role);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select u from UserAccount u where u.role = :role and u.enabled = true order by u.id") List<UserAccount> lockEnabledAdministrators(@Param("role") UserRole role);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select u from UserAccount u where u.id = :id") Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);
}

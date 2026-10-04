package com.dictalab.repository;

import com.dictalab.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> lockById(@Param("id") Long id);

    @Query("""
            SELECT u FROM AppUser u WHERE
                (LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%'))
                    OR LOWER(u.displayName) LIKE LOWER(CONCAT('%', :query, '%')))
                AND (:role IS NULL OR u.role = :role)
                AND (:enabled IS NULL OR u.enabled = :enabled)
            """)
    Page<AppUser> search(@Param("query") String query, @Param("role") String role,
                         @Param("enabled") Boolean enabled, Pageable pageable);

    long countByRoleAndEnabledTrue(String role);
}

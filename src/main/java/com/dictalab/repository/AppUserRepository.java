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

    Optional<AppUser> findByGoogleSubject(String subject);

    boolean existsByEmail(String email);

    interface AuthenticationState {
        Long getId();
        String getEmail();
        String getDisplayName();
        String getRole();
        boolean isEnabled();
        long getAuthVersion();
    }

    // A single authoritative read; declared queries do not open the CRUD read-only transaction.
    @Query("""
            select u.id as id, u.email as email, u.displayName as displayName, u.role as role,
                   u.enabled as enabled, u.authVersion as authVersion
            from AppUser u where u.id = :id
            """)
    Optional<AuthenticationState> authenticationState(@Param("id") Long id);

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

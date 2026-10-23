package com.propertypro.repository;

import com.propertypro.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Emails and phones are unique across the whole platform (ux_users_email / ux_users_phone),
     * so call these as the system (SystemExecutor.asSystem): under RLS only your own
     * organisation's users are visible.
     */
    @Query(value = "SELECT count(*) > 0 FROM users WHERE lower(email::text) = lower(:email) AND deleted_at IS NULL",
            nativeQuery = true)
    boolean emailTaken(@Param("email") String email);

    @Query(value = "SELECT count(*) > 0 FROM users WHERE phone = :phone AND deleted_at IS NULL", nativeQuery = true)
    boolean phoneTaken(@Param("phone") String phone);

    @Query("""
            select u from User u
             where lower(u.email) = lower(:email)
               and u.deletedAt is null
               and u.status = :status
               and u.passwordHash is not null
            """)
    List<User> findByEmailAndStatus(@Param("email") String email, @Param("status") User.Status status);

    /** Login candidates across all organisations. Must run in system context. */
    default List<User> findLoginCandidates(String email) {
        return findByEmailAndStatus(email, User.Status.ACTIVE);
    }

    @Query(value = """
            SELECT DISTINCT rp.permission_code
              FROM user_roles ur
              JOIN role_permissions rp ON rp.role_id = ur.role_id
             WHERE ur.user_id = :userId
            """, nativeQuery = true)
    List<String> findPermissionCodes(@Param("userId") UUID userId);

    @Query(value = """
            SELECT r.code
              FROM user_roles ur
              JOIN roles r ON r.id = ur.role_id
             WHERE ur.user_id = :userId
             ORDER BY r.code
            """, nativeQuery = true)
    List<String> findRoleCodes(@Param("userId") UUID userId);

    @Modifying
    @Query(value = """
            INSERT INTO user_roles (user_id, role_id, organization_id)
            SELECT :userId, r.id, :organizationId
              FROM roles r
             WHERE r.code = :roleCode
               AND (r.organization_id IS NULL OR r.organization_id = :organizationId)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int assignRole(@Param("userId") UUID userId,
                   @Param("organizationId") UUID organizationId,
                   @Param("roleCode") String roleCode);
}

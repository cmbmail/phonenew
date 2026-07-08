package com.phonecost.repository;

import com.phonecost.domain.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SysUserRepository extends JpaRepository<SysUser, Long> {
    Optional<SysUser> findByUsernameAndDeletedAtIsNull(String username);
    List<SysUser> findByOrgIdAndDeletedAtIsNull(Long orgId);
    List<SysUser> findByOrgIdInAndDeletedAtIsNull(List<Long> orgIds);
    boolean existsByUsernameAndDeletedAtIsNull(String username);

    Optional<SysUser> findByIdAndDeletedAtIsNull(Long id);

    List<SysUser> findByDeletedAtIsNull();

    /** Count users by org IDs without loading all entities */
    long countByOrgIdInAndDeletedAtIsNull(List<Long> orgIds);

    /** Paginated query for users by org IDs */
    org.springframework.data.domain.Page<SysUser> findByOrgIdInAndDeletedAtIsNull(List<Long> orgIds, org.springframework.data.domain.Pageable pageable);

    /** Paginated query for all active users */
    org.springframework.data.domain.Page<SysUser> findByDeletedAtIsNull(org.springframework.data.domain.Pageable pageable);
}

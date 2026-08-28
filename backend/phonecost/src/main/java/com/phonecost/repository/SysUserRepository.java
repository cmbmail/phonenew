package com.phonecost.repository;

import com.phonecost.domain.SysUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SysUserRepository extends JpaRepository<SysUser, Long> {
    Optional<SysUser> findByUsernameAndDeletedAtIsNull(String username);
    boolean existsByUsernameAndDeletedAtIsNull(String username);

    Optional<SysUser> findByIdAndDeletedAtIsNull(Long id);


    /** Count users by org IDs without loading all entities */
    long countByOrgIdInAndDeletedAtIsNull(List<Long> orgIds);

    /** Dynamic search: username/realName fuzzy match + orgId list filter */
    @Query("SELECT u FROM SysUser u WHERE u.deletedAt IS NULL AND " +
           "(:username IS NULL OR LOWER(u.username) LIKE LOWER(CONCAT('%', :username, '%'))) AND " +
           "(:realName IS NULL OR LOWER(u.realName) LIKE LOWER(CONCAT('%', :realName, '%'))) AND " +
           "(:orgIds IS NULL OR u.orgId IN :orgIds)")
    Page<SysUser> searchPaged(
            @Param("username") String username,
            @Param("realName") String realName,
            @Param("orgIds") List<Long> orgIds,
            Pageable pageable);
}

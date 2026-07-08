package com.phonecost.repository;

import com.phonecost.domain.SysOrganization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SysOrganizationRepository extends JpaRepository<SysOrganization, Long> {
    List<SysOrganization> findByParentIdAndDeletedAtIsNull(Long parentId);
    List<SysOrganization> findByTypeAndDeletedAtIsNull(Byte type);
    Optional<SysOrganization> findByCodeAndDeletedAtIsNull(String code);
    List<SysOrganization> findByPathStartingWithAndDeletedAtIsNull(String path);
    Optional<SysOrganization> findByIdAndDeletedAtIsNull(Long id);

    List<SysOrganization> findByDeletedAtIsNull();

    boolean existsByIdAndDeletedAtIsNull(Long id);

    @Query("SELECT o FROM SysOrganization o WHERE o.path LIKE CONCAT(:parentPath, '%') AND o.deletedAt IS NULL")
    List<SysOrganization> findAllDescendants(@Param("parentPath") String parentPath);

    /** M-07/M-08: Count branches by type without loading all entities */
    long countByTypeAndDeletedAtIsNull(Byte type);

    /** M-08: Build code-to-name map without loading all entity fields */
    @Query("SELECT o.code, o.name FROM SysOrganization o WHERE o.code IS NOT NULL AND o.deletedAt IS NULL")
    List<Object[]> findCodeNamePairs();

    /** M-07: Build orgId-to-type map for dashboard without loading all entities */
    @Query("SELECT o.id, o.type FROM SysOrganization o WHERE o.type IS NOT NULL AND o.deletedAt IS NULL")
    List<Object[]> findIdTypePairs();

    /** Count branches (type=2) within a set of org IDs without loading entities */
    long countByTypeAndIdInAndDeletedAtIsNull(Byte type, List<Long> ids);
}

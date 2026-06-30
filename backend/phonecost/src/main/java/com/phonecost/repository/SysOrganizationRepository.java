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
    boolean existsByIdAndDeletedAtIsNull(Long id);

    @Query("SELECT o FROM SysOrganization o WHERE o.path LIKE CONCAT(:parentPath, '%') AND o.deletedAt IS NULL")
    List<SysOrganization> findAllDescendants(@Param("parentPath") String parentPath);
}

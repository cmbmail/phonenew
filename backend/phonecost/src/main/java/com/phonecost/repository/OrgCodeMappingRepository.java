package com.phonecost.repository;

import com.phonecost.domain.OrgCodeMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrgCodeMappingRepository extends JpaRepository<OrgCodeMapping, Long> {

    Optional<OrgCodeMapping> findByIdAndDeletedAtIsNull(Long id);

    Optional<OrgCodeMapping> findByOrgCodeAndDeletedAtIsNull(String orgCode);

    /** 查询 org_code 对应记录（含软删除），用于导入 upsert 时避免唯一索引冲突 */
    Optional<OrgCodeMapping> findByOrgCode(String orgCode);

    Page<OrgCodeMapping> findByDeletedAtIsNull(Pageable pageable);

    @Query("SELECT m FROM OrgCodeMapping m WHERE m.deletedAt IS NULL " +
           "AND (m.l1Branch LIKE %:keyword% OR m.orgCode LIKE %:keyword% OR m.orgName LIKE %:keyword% " +
           "OR m.costCenterCode LIKE %:keyword% OR m.remark LIKE %:keyword%) " +
           "ORDER BY m.id")
    Page<OrgCodeMapping> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    List<OrgCodeMapping> findAllByDeletedAtIsNull();

    @Query("SELECT m FROM OrgCodeMapping m WHERE m.deletedAt IS NULL ORDER BY m.id")
    List<OrgCodeMapping> findAllForExport();
}
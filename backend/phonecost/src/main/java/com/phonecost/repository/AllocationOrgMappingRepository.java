package com.phonecost.repository;

import com.phonecost.domain.AllocationOrgMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AllocationOrgMappingRepository extends JpaRepository<AllocationOrgMapping, Long> {

    Optional<AllocationOrgMapping> findByIdAndDeletedAtIsNull(Long id);

    /** 按 一级分行+机构名称 定位记录（导入 upsert 键；机构名称/代码/成本中心可重复） */
    Optional<AllocationOrgMapping> findByL1BranchAndOrgNameAndDeletedAtIsNull(String l1Branch, String orgName);

    Page<AllocationOrgMapping> findByDeletedAtIsNull(Pageable pageable);

    @Query("SELECT m FROM AllocationOrgMapping m WHERE m.deletedAt IS NULL " +
           "AND (m.l1Branch LIKE %:keyword% OR m.orgName LIKE %:keyword% OR m.orgCode LIKE %:keyword% " +
           "OR m.costCenterCode LIKE %:keyword% OR m.deptFullPath LIKE %:keyword% OR m.remark LIKE %:keyword%) " +
           "ORDER BY m.id")
    Page<AllocationOrgMapping> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    List<AllocationOrgMapping> findAllByDeletedAtIsNull();

    @Query("SELECT m FROM AllocationOrgMapping m WHERE m.deletedAt IS NULL ORDER BY m.id")
    List<AllocationOrgMapping> findAllForExport();
}

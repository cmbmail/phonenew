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

    /** 按机构名称查询（含软删除，避免唯一索引冲突） */
    Optional<AllocationOrgMapping> findByOrgName(String orgName);

    /** 按机构代码查询（含软删除，避免唯一索引冲突） */
    Optional<AllocationOrgMapping> findByOrgCode(String orgCode);

    /** 按成本中心代码查询（含软删除，避免唯一索引冲突） */
    Optional<AllocationOrgMapping> findByCostCenterCode(String costCenterCode);

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

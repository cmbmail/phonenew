package com.phonecost.service;

import com.phonecost.domain.*;
import com.phonecost.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 归属计算引擎 v2
 *
 * 匹配优先级（统一用 phone_number 匹配 phone_ownership_entry）：
 *   P0: 例外号码 (is_exception=1) — 直接使用 entry.orgId
 *   P1: 正常号码归属 (is_exception=0) — 优先通过 org_code 匹配组织，降级到 l1_branch 分行名匹配
 *   P2: 降级匹配 — 号码在 ownership 中有 full_path 但无 alloc_dept，通过 full_path 匹配成本中心
 *   P3: 未归属 — 所有数据源都没有该号码
 *
 * "按月归集"：所有数据均按账单的 billing_month 加载同月数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnershipMatchService {

    private final PhoneOwnershipEntryRepository ownershipEntryRepository;
    private final AllocationDeptEntryRepository allocationDeptEntryRepository;
    private final BillDetailRepository billDetailRepository;
    private final BillBatchRepository billBatchRepository;
    private final SysOrganizationRepository orgRepository;
    private final DirectoryEntryRepository directoryEntryRepository;
    private final DirectoryBatchRepository directoryBatchRepository;

    /**
     * Normalize phone number: strip all non-digit characters
     */
    private String normalizePhone(String phone) {
        if (phone == null) return "";
        return phone.replaceAll("[^0-9]", "");
    }

    /**
     * 对指定账单批次的所有明细执行归属匹配
     *
     * @param billBatchId              账单批次ID
     * @param ownershipBatchId         号码归属批次ID（可选，null时自动按月查找）
     * @param directoryBatchId         通讯录批次ID（已不再使用，保留参数兼容）
     * @param allocationDeptBatchId    归属部门批次ID（可选，null时自动按月查找）
     * @return 匹配的号码数量
     */
    @Transactional
    public int matchOwnershipForBillBatch(Long billBatchId,
                                          Long ownershipBatchId,
                                          Long directoryBatchId,
                                          Long allocationDeptBatchId) {
        // Get bill batch to determine billing_month
        BillBatch billBatch = billBatchRepository.findByIdAndDeletedAtIsNull(billBatchId)
                .orElseThrow(() -> new IllegalArgumentException("账单批次不存在: " + billBatchId));
        String billingMonth = billBatch.getBillingMonth();

        // Load ownership entries — auto by billing_month if batch not specified
        List<PhoneOwnershipEntry> ownershipEntries;
        if (ownershipBatchId != null) {
            ownershipEntries = ownershipEntryRepository.findByBatchIdAndDeletedAtIsNull(ownershipBatchId);
        } else {
            ownershipEntries = ownershipEntryRepository.findByBillingMonth(billingMonth,
                    org.springframework.data.domain.Pageable.unpaged()).getContent();
            log.info("Auto-loaded ownership entries for billing_month={}, count={}", billingMonth, ownershipEntries.size());
        }

        // Preload all organizations into maps for O(1) lookup
        Map<String, SysOrganization> orgByCode = new HashMap<>();
        Map<String, SysOrganization> orgByCostCenter = new HashMap<>();
        List<SysOrganization> allOrgs = orgRepository.findByDeletedAtIsNull();
        Map<String, SysOrganization> branchByName = new HashMap<>();
        for (SysOrganization org : allOrgs) {
            if (org.getCode() != null && !org.getCode().isEmpty()) {
                orgByCode.putIfAbsent(org.getCode(), org);
            }
            if (org.getCostCenter() != null && !org.getCostCenter().isEmpty()) {
                orgByCostCenter.putIfAbsent(org.getCostCenter(), org);
            }
            if (org.getType() == 2 && org.getName() != null) {
                branchByName.putIfAbsent(org.getName(), org);
            }
        }
        log.info("Preloaded {} orgs: {} by code, {} by cost_center, {} branches",
                allOrgs.size(), orgByCode.size(), orgByCostCenter.size(), branchByName.size());

        // Build lookup maps using NORMALIZED phone numbers as keys
        // exceptionMap: P0 - 例外号码 (is_exception=1)
        // ownershipMap: P1 - 正常号码 (is_exception=0)
        Map<String, PhoneOwnershipEntry> exceptionMap = new HashMap<>();
        Map<String, PhoneOwnershipEntry> ownershipMap = new HashMap<>();
        for (PhoneOwnershipEntry entry : ownershipEntries) {
            String key = normalizePhone(entry.getPhoneNumber());
            if (key.isEmpty()) continue;
            if (Byte.valueOf((byte)1).equals(entry.getIsException())) {
                exceptionMap.putIfAbsent(key, entry);
            } else {
                ownershipMap.putIfAbsent(key, entry);
            }
        }

        // Load allocation dept entries for P2 fallback
        Map<String, AllocationDeptEntry> allocDeptMap = buildAllocDeptFullPathMap(billingMonth, allocationDeptBatchId);

        // Load directory entries for P2 fallback: build normalized-phone → dept_path map
        // This allows P2 to find full_path from directory even when ownership data is missing
        Map<String, String> phoneToDeptPath = buildPhoneToDeptPathMap(billingMonth, directoryBatchId);

        log.info("Ownership matching v2: batch={}, billingMonth={}, P0(exception)={}, P1(ownership)={}, P2(allocDept)={}, dirPaths={}",
                billBatchId, billingMonth, exceptionMap.size(), ownershipMap.size(), allocDeptMap.size(), phoneToDeptPath.size());

        // Process bill details in pages to avoid OOM (H-B03)
        int matched = 0;
        int p0Count = 0, p1Count = 0, p2Count = 0, p3Count = 0;
        final int PAGE_SIZE = 5000;
        int page = 0;
        long totalProcessed = 0;

        while (true) {
            org.springframework.data.domain.Page<BillDetail> detailPage =
                    billDetailRepository.findByBatchIdAndDeletedAtIsNull(
                            billBatchId,
                            org.springframework.data.domain.PageRequest.of(page, PAGE_SIZE));
            List<BillDetail> details = detailPage.getContent();
            if (details.isEmpty()) break;

            for (BillDetail detail : details) {
                String key = normalizePhone(detail.getPhoneNumber());

                // P0: 例外号码 (is_exception=1)
                if (exceptionMap.containsKey(key)) {
                    PhoneOwnershipEntry entry = exceptionMap.get(key);
                    detail.setOwnershipSource("P0");
                    detail.setIsException((byte) 1);
                    Long orgId = resolveOrgId(entry, orgByCode, branchByName);
                    detail.setOrgId(orgId);
                    matched++;
                    p0Count++;
                    continue;
                }

                // P1: 正常号码归属 (is_exception=0)
                if (ownershipMap.containsKey(key)) {
                    PhoneOwnershipEntry entry = ownershipMap.get(key);
                    detail.setOwnershipSource("P1");
                    detail.setIsException((byte) 0);
                    Long orgId = resolveOrgId(entry, orgByCode, branchByName);
                    detail.setOrgId(orgId);
                    matched++;
                    p1Count++;
                    continue;
                }

                // P2: 降级 — 号码不在 ownership 中，通过通讯录 dept_path 匹配成本中心
                String deptPath = phoneToDeptPath.get(key);
                if (deptPath != null && !deptPath.isEmpty() && allocDeptMap.containsKey(deptPath)) {
                    AllocationDeptEntry allocEntry = allocDeptMap.get(deptPath);
                    detail.setOwnershipSource("P2");
                    detail.setIsException((byte) 0);
                    Long orgId = matchOrgByAllocDept(allocEntry, orgByCode, orgByCostCenter, branchByName);
                    detail.setOrgId(orgId);
                    matched++;
                    p2Count++;
                    continue;
                }

                // P3: 未归属
                detail.setOwnershipSource("P3");
                detail.setOrgId(null);
                detail.setIsException((byte) 0);
                p3Count++;
            }

            // Batch save this page
            billDetailRepository.saveAll(details);
            totalProcessed += details.size();
            log.info("Ownership matching progress: page={}, processed={}", page, totalProcessed);

            if (!detailPage.hasNext()) break;
            page++;
        }

        log.info("Ownership matching v2 completed: batch={}, P0={}, P1={}, P2={}, P3={}, total={}",
                billBatchId, p0Count, p1Count, p2Count, p3Count, totalProcessed);

        return matched;
    }

    /**
     * 从 PhoneOwnershipEntry 解析 org_id
     * 优先级：entry.orgId > org_code 查表 > l1_branch 查表
     */
    private Long resolveOrgId(PhoneOwnershipEntry entry,
                              Map<String, SysOrganization> orgByCode,
                              Map<String, SysOrganization> branchByName) {
        // 1. 直接使用 orgId
        if (entry.getOrgId() != null) {
            return entry.getOrgId();
        }

        // 2. 通过 org_code 匹配
        String orgCode = entry.getOrgCode();
        if (orgCode != null && !orgCode.isEmpty()) {
            SysOrganization org = orgByCode.get(orgCode);
            if (org != null) {
                return org.getId();
            }
        }

        // 3. 通过 l1_branch 名称匹配分行
        String l1Branch = entry.getL1Branch();
        if (l1Branch != null && !l1Branch.isEmpty()) {
            SysOrganization branch = branchByName.get(l1Branch);
            if (branch != null) {
                return branch.getId();
            }
            // 模糊匹配
            for (Map.Entry<String, SysOrganization> e : branchByName.entrySet()) {
                if (e.getKey().contains(l1Branch) || l1Branch.contains(e.getKey())) {
                    return e.getValue().getId();
                }
            }
        }

        return null;
    }

    /**
     * 构建通讯录的号码→部门全路径查找Map（P2降级用）
     * 使用规范化号码作为key，dept_path作为value
     */
    private Map<String, String> buildPhoneToDeptPathMap(String billingMonth, Long directoryBatchId) {
        Map<String, String> map = new LinkedHashMap<>();
        List<DirectoryEntry> entries;
        if (directoryBatchId != null) {
            entries = directoryEntryRepository.findByBatchIdAndDeletedAtIsNull(directoryBatchId);
        } else {
            List<DirectoryBatch> batches = directoryBatchRepository.findByBillingMonthAndDeletedAtIsNull(billingMonth);
            List<Long> batchIds = batches.stream().map(DirectoryBatch::getId).collect(Collectors.toList());
            entries = directoryEntryRepository.findByBatchIdInAndDeletedAtIsNull(batchIds);
        }
        for (DirectoryEntry entry : entries) {
            String phone = normalizePhone(entry.getPhoneNumber());
            if (!phone.isEmpty() && entry.getDeptPath() != null && !entry.getDeptPath().isEmpty()) {
                map.putIfAbsent(phone, entry.getDeptPath());
            }
        }
        log.info("Built phone→deptPath map: billingMonth={}, dirBatchId={}, entries={}", billingMonth, directoryBatchId, map.size());
        return map;
    }

    /**
     * 构建归属部门的full_path查找Map（按月归集）
     */
    private Map<String, AllocationDeptEntry> buildAllocDeptFullPathMap(String billingMonth, Long batchId) {
        Map<String, AllocationDeptEntry> map = new LinkedHashMap<>();
        List<AllocationDeptEntry> allEntries;
        if (batchId != null) {
            allEntries = allocationDeptEntryRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        } else {
            allEntries = allocationDeptEntryRepository.findAllByBillingMonth(billingMonth);
        }
        for (AllocationDeptEntry entry : allEntries) {
            String key = entry.getFullPath();
            if (key != null && !key.isEmpty()) {
                map.putIfAbsent(key, entry);
            }
        }
        log.info("Built allocDept map: billingMonth={}, batchId={}, entries={}", billingMonth, batchId, map.size());
        return map;
    }

    /**
     * 通过分摊部门匹配组织（使用预加载 Map）
     */
    private Long matchOrgByAllocDept(AllocationDeptEntry allocEntry,
                                     Map<String, SysOrganization> orgByCode,
                                     Map<String, SysOrganization> orgByCostCenter,
                                     Map<String, SysOrganization> branchByName) {
        // 1. 通过 org_code 匹配
        if (allocEntry.getOrgCode() != null && !allocEntry.getOrgCode().isEmpty()) {
            SysOrganization org = orgByCode.get(allocEntry.getOrgCode());
            if (org != null) return org.getId();
        }
        // 2. 通过 cost_center 匹配
        if (allocEntry.getCostCenter() != null && !allocEntry.getCostCenter().isEmpty()) {
            SysOrganization org = orgByCostCenter.get(allocEntry.getCostCenter());
            if (org != null) return org.getId();
        }
        // 3. 通过 branch 名称匹配
        String branch = allocEntry.getBranch();
        if (branch != null && !branch.isEmpty()) {
            SysOrganization org = branchByName.get(branch);
            if (org != null) return org.getId();
            for (Map.Entry<String, SysOrganization> e : branchByName.entrySet()) {
                if (e.getKey().contains(branch) || branch.contains(e.getKey())) {
                    return e.getValue().getId();
                }
            }
        }
        return null;
    }
}

package com.phonecost.service;

import com.phonecost.domain.*;
import com.phonecost.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 归属计算引擎
 * 对每个电话号码，按优先级确定归属组织：
 * P0: 号码归属表例外标记 (is_exception=1) — 最高优先级
 * P1: 通讯录 部门全路径 — 最细粒度(到员工/部门)
 * P2: 号码归属表 描述 — 兜底(通常只到分行级)
 * P3: 未归属 — 两个数据源都没有该号码
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnershipMatchService {

    private final PhoneOwnershipEntryRepository ownershipEntryRepository;
    private final DirectoryEntryRepository directoryEntryRepository;
    private final BillDetailRepository billDetailRepository;
    private final SysOrganizationRepository orgRepository;

    /**
     * 对指定账单批次的所有明细执行归属匹配
     * 查找最新的号码归属批次和通讯录批次
     */
    @Transactional
    public int matchOwnershipForBillBatch(Long billBatchId,
                                          Long ownershipBatchId,
                                          Long directoryBatchId) {
        // Load ownership entries (latest batch)
        List<PhoneOwnershipEntry> ownershipEntries = ownershipBatchId != null
                ? ownershipEntryRepository.findByBatchIdAndDeletedAtIsNull(ownershipBatchId)
                : Collections.emptyList();

        // Load directory entries (latest batch)
        List<DirectoryEntry> directoryEntries = directoryBatchId != null
                ? directoryEntryRepository.findByBatchIdAndDeletedAtIsNull(directoryBatchId)
                : Collections.emptyList();

        // Build lookup maps
        // Map: phoneNumber -> ownership entry (for P0/P2 matching)
        Map<String, PhoneOwnershipEntry> ownershipMap = new HashMap<>();
        Map<String, PhoneOwnershipEntry> exceptionMap = new HashMap<>();
        for (PhoneOwnershipEntry entry : ownershipEntries) {
            String phone = entry.getPhoneNumber();
            if (entry.getIsException() == (byte) 1) {
                exceptionMap.put(phone, entry); // P0 entries
            } else {
                ownershipMap.put(phone, entry); // P2 entries
            }
        }

        // Map: phoneNumber -> directory entry (for P1 matching)
        // Note: one phone number may have multiple directory entries (shared lines)
        // Use the first match (most specific)
        Map<String, DirectoryEntry> directoryMap = new HashMap<>();
        for (DirectoryEntry entry : directoryEntries) {
            String phone = entry.getPhoneNumber();
            if (phone != null && !phone.isEmpty() && !directoryMap.containsKey(phone)) {
                directoryMap.put(phone, entry);
            }
        }

        // Process all bill details
        List<BillDetail> details = billDetailRepository.findByBatchIdAndDeletedAtIsNull(billBatchId);
        int matched = 0;
        int unmatched = 0;

        for (BillDetail detail : details) {
            String phone = detail.getPhoneNumber();

            // P0: Exception marker in ownership table
            if (exceptionMap.containsKey(phone)) {
                PhoneOwnershipEntry entry = exceptionMap.get(phone);
                detail.setOwnershipSource("P0");
                detail.setIsException((byte) 1);
                detail.setOrgId(entry.getOrgId());
                matched++;
                continue;
            }

            // P1: Directory match (finest granularity)
            if (directoryMap.containsKey(phone)) {
                DirectoryEntry entry = directoryMap.get(phone);
                detail.setOwnershipSource("P1");
                detail.setIsException((byte) 0);
                detail.setIsSeconded(entry.getIsSeconded());
                detail.setOrgId(entry.getOrgId());
                matched++;
                continue;
            }

            // P2: Ownership table match (fallback, usually branch-level)
            if (ownershipMap.containsKey(phone)) {
                PhoneOwnershipEntry entry = ownershipMap.get(phone);
                detail.setOwnershipSource("P2");
                detail.setIsException((byte) 0);
                detail.setOrgId(entry.getOrgId());
                matched++;
                continue;
            }

            // P3: No match found
            detail.setOwnershipSource("P3");
            detail.setOrgId(null);
            unmatched++;
        }

        // Batch save all updated details
        billDetailRepository.saveAll(details);

        log.info("Ownership matching completed: batch={}, matched={}, unmatched={}",
                billBatchId, matched, unmatched);

        return matched;
    }

    /**
     * Parse ownership description to find org by name
     * Description format: "贵阳分行/遵义分行" (branch names separated by /)
     * Try to match org by name
     */
    public Long matchOrgByDescription(String description) {
        if (description == null || description.isEmpty()) return null;

        // Remove [例外] prefix if present
        String desc = description.replace("[例外]", "").trim();

        // Try each segment separated by /
        String[] segments = desc.split("/");
        for (String segment : segments) {
            String name = segment.trim();
            if (name.isEmpty()) continue;

            // Try exact name match
            List<SysOrganization> orgs = orgRepository.findByTypeAndDeletedAtIsNull((byte) 2); // Branch1
            for (SysOrganization org : orgs) {
                if (org.getName().equals(name)) {
                    return org.getId();
                }
            }

            // Try partial match (name contains org name or vice versa)
            for (SysOrganization org : orgs) {
                if (org.getName().contains(name) || name.contains(org.getName())) {
                    return org.getId();
                }
            }
        }

        return null;
    }
}

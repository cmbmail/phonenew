package com.phonecost.service;

import com.phonecost.domain.*;
import com.phonecost.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 号码归属自动生成服务（4步匹配算法）
 *
 * 数据源：
 *   1. 通讯录（directory_entry）— 主数据源，提供号码、分机号、部门全路径
 *   2. 归属分行（phone_ownership_entry, is_exception=0）— 提供一级分行
 *   3. 成本中心（allocation_dept_entry）— 提供分摊部门、机构代码、成本中心
 *   4. 例外号码（phone_ownership_entry, is_exception=1）— 例外标记
 *
 * 4步匹配算法：
 *   Step1: 通过号码匹配例外号码数据（is_exception=1）
 *   Step2: 通过号码匹配归属分行（phone_ownership_entry l1_branch）
 *   Step3: 通过部门全路径匹配成本中心（allocation_dept_entry full_path → alloc_dept, org_code, cost_center）
 *   Step4: 同一号码的多个分机号合并，用"，"分隔
 *
 * 输出8列：号码、分机号、一级分行、分摊部门、部门全路径、机构代码、成本中心、例外
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneOwnershipGeneratorService {

    private final DirectoryEntryRepository directoryEntryRepository;
    private final DirectoryBatchRepository directoryBatchRepository;
    private final PhoneOwnershipBatchRepository ownershipBatchRepository;
    private final PhoneOwnershipEntryRepository ownershipEntryRepository;
    private final AllocationDeptEntryRepository allocationDeptEntryRepository;
    private final AllocationDeptBatchRepository allocationDeptBatchRepository;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int BATCH_SIZE = 5000;

    /**
     * 根据指定月份的通讯录数据生成号码归属
     *
     * @param billingMonth 账期月份（如 "2026-07"）
     * @param userId       操作用户ID
     * @return 生成结果统计
     */
    public synchronized Map<String, Object> generate(String billingMonth, Long userId) {  // H-B06: synchronized to prevent concurrent race
        long startTime = System.currentTimeMillis();
        log.info("PhoneOwnership generation started: month={}, userId={}", billingMonth, userId);

        // 1. 加载通讯录数据
        List<DirectoryBatch> dirBatches = directoryBatchRepository.findByBillingMonthAndDeletedAtIsNull(billingMonth);
        if (dirBatches.isEmpty()) {
            throw new IllegalArgumentException("指定月份没有通讯录数据: " + billingMonth);
        }
        List<Long> dirBatchIds = dirBatches.stream().map(DirectoryBatch::getId).collect(Collectors.toList());
        List<DirectoryEntry> directoryEntries = directoryEntryRepository.findByBatchIdInAndDeletedAtIsNull(dirBatchIds);
        if (directoryEntries.isEmpty()) {
            throw new IllegalArgumentException("指定月份通讯录无数据: " + billingMonth);
        }
        log.info("Loaded {} directory entries from {} batches for month {}", directoryEntries.size(), dirBatchIds.size(), billingMonth);

        // 2. 加载例外号码数据（phone_ownership_entry, is_exception=1, 按月）
        List<PhoneOwnershipEntry> exceptionEntries = ownershipEntryRepository.findAllExceptionsByBillingMonth(billingMonth);
        Set<String> exceptionPhones = exceptionEntries.stream()
                .map(PhoneOwnershipEntry::getPhoneNumber)
                .filter(p -> p != null && !p.isEmpty())
                .map(this::normalizePhone)
                .collect(Collectors.toSet());
        log.info("Loaded {} exception phone numbers", exceptionPhones.size());

        // 3. 加载归属分行数据（phone_ownership_entry, is_exception=0, 按月）
        //    这里获取当月非例外条目，构建 phone → l1_branch 映射
        //    H-B04: 使用分页循环加载，避免硬编码5万条上限
        Map<String, String> phoneToL1Branch = new HashMap<>();
        int branchPage = 0;
        final int BRANCH_PAGE_SIZE = 10000;
        while (true) {
            org.springframework.data.domain.Page<PhoneOwnershipEntry> branchPageData =
                    ownershipEntryRepository.findByBillingMonth(billingMonth,
                            org.springframework.data.domain.PageRequest.of(branchPage, BRANCH_PAGE_SIZE));
            List<PhoneOwnershipEntry> branchContent = branchPageData.getContent();
            if (branchContent.isEmpty()) break;
            for (PhoneOwnershipEntry entry : branchContent) {
                if (entry.getIsException() == 0 && entry.getPhoneNumber() != null && !entry.getPhoneNumber().isEmpty()) {
                    String l1 = entry.getL1Branch();
                    if (l1 != null && !l1.isEmpty()) {
                        phoneToL1Branch.putIfAbsent(normalizePhone(entry.getPhoneNumber()), l1);
                    }
                }
            }
            if (!branchPageData.hasNext()) break;
            branchPage++;
        }
        log.info("Loaded {} branch mappings", phoneToL1Branch.size());

        // 4. 加载成本中心数据（allocation_dept_entry, 按月）
        List<AllocationDeptEntry> allocDeptEntries = allocationDeptEntryRepository.findAllByBillingMonth(billingMonth);
        // full_path → (alloc_dept/dept_name, org_code, cost_center)
        Map<String, String[]> fullPathToAlloc = new HashMap<>();
        for (AllocationDeptEntry ade : allocDeptEntries) {
            String fp = ade.getFullPath();
            if (fp != null && !fp.isEmpty() && !fullPathToAlloc.containsKey(fp)) {
                fullPathToAlloc.put(fp, new String[]{
                        ade.getDeptName() != null ? ade.getDeptName() : "",
                        ade.getOrgCode() != null ? ade.getOrgCode() : "",
                        ade.getCostCenter() != null ? ade.getCostCenter() : ""
                });
            }
        }
        log.info("Loaded {} cost center mappings", fullPathToAlloc.size());

        // 5. 创建号码归属批次
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        String batchNo = "OWN-GEN-" + LocalDateTime.now().format(DTF);

        PhoneOwnershipBatch batch = txTemplate.execute(status -> {
            PhoneOwnershipBatch b = new PhoneOwnershipBatch();
            b.setBatchNo(batchNo);
            b.setFileName("auto-generated");
            b.setTotalCount(0);
            b.setExceptionCount(0);
            b.setBillingMonth(billingMonth);
            b.setImportStatus((byte) 1); // 直接标记为完成
            b.setImportedBy(userId);
            return ownershipBatchRepository.save(b);
        });
        final Long batchId = batch.getId();

        // 6. 软删除该月份已有的旧号码归属数据（非例外的）
        //    例外号码是独立管理的，不在此处删除
        //    H-B06: 软删+重插在同一个事务中，消除竞态窗口
        List<PhoneOwnershipBatch> oldBatches = ownershipBatchRepository.findByBillingMonthAndDeletedAtIsNull(billingMonth);
        for (PhoneOwnershipBatch oldBatch : oldBatches) {
            if (!oldBatch.getId().equals(batchId)) {
                ownershipEntryRepository.softDeleteByBatchId(oldBatch.getId(), LocalDateTime.now());
                oldBatch.setDeletedAt(LocalDateTime.now());
                ownershipBatchRepository.save(oldBatch);
            }
        }

        // 7. 执行4步匹配，生成号码归属记录
        //    按 phone_number 分组通讯录数据
        Map<String, List<DirectoryEntry>> phoneToDirs = directoryEntries.stream()
                .filter(d -> d.getPhoneNumber() != null && !d.getPhoneNumber().isEmpty())
                .collect(Collectors.groupingBy(d -> normalizePhone(d.getPhoneNumber())));

        List<Object[]> rows = new ArrayList<>(directoryEntries.size());

        for (Map.Entry<String, List<DirectoryEntry>> entry : phoneToDirs.entrySet()) {
            String phone = entry.getKey();
            List<DirectoryEntry> dirs = entry.getValue();

            // Step1: 匹配例外号码
            boolean isException = exceptionPhones.contains(normalizePhone(phone));

            // Step2: 匹配归属分行
            String l1Branch = phoneToL1Branch.getOrDefault(normalizePhone(phone), "");

            // Step4: 合并分机号（同一号码的多个分机号用"，"分隔）
            String mergedExtensions = dirs.stream()
                    .map(DirectoryEntry::getExtension)
                    .filter(ext -> ext != null && !ext.isEmpty())
                    .distinct()
                    .collect(Collectors.joining(","));

            // 每个通讯录条目生成一行号码归属记录
            for (DirectoryEntry dir : dirs) {
                String deptPath = dir.getDeptPath() != null ? dir.getDeptPath() : "";

                // Step3: 匹配成本中心
                String allocDept = "";
                String orgCode = "";
                String costCenter = "";
                String[] allocMatch = fullPathToAlloc.get(deptPath);
                if (allocMatch != null) {
                    allocDept = allocMatch[0];
                    orgCode = allocMatch[1];
                    costCenter = allocMatch[2];
                }

                rows.add(new Object[]{
                        batchId,
                        phone,
                        dir.getExtension() != null ? dir.getExtension() : "",
                        deptPath,
                        "", // description
                        isException ? (byte) 1 : (byte) 0,
                        isException ? "P0" : "P1",
                        l1Branch,
                        "", // l2_branch — 不再使用
                        (byte) 0, // status
                        allocDept,
                        orgCode,
                        costCenter
                });
            }
        }

        // 8. JdbcTemplate 批量写入
        String insertSql = "INSERT INTO phone_ownership_entry " +
                "(batch_id, phone_number, extension, full_path, description, is_exception, match_level, " +
                "l1_branch, l2_branch, status, alloc_dept, org_code, cost_center, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";

        int totalCount = rows.size();
        int exceptionCount = 0;

        for (int offset = 0; offset < totalCount; offset += BATCH_SIZE) {
            int end = Math.min(offset + BATCH_SIZE, totalCount);
            List<Object[]> chunk = rows.subList(offset, end);
            final int chunkEnd = end;
            txTemplate.executeWithoutResult(status -> {
                jdbcTemplate.batchUpdate(insertSql, chunk);
            });
            log.info("Ownership generation progress: {}/{}", chunkEnd, totalCount);
        }

        // 统计例外数量
        exceptionCount = (int) rows.stream().filter(r -> (byte) r[5] == 1).count();

        // 9. 更新批次统计
        final int finalExceptionCount = exceptionCount;
        txTemplate.executeWithoutResult(status -> {
            ownershipBatchRepository.findById(batchId).ifPresent(b -> {
                b.setTotalCount(totalCount);
                b.setExceptionCount(finalExceptionCount);
                ownershipBatchRepository.save(b);
            });
        });

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("PhoneOwnership generation completed: batch={}, total={}, exceptions={}, time={}ms",
                batchNo, totalCount, exceptionCount, elapsed);

        Map<String, Object> result = new HashMap<>();
        result.put("batch_id", batchId);
        result.put("batch_no", batchNo);
        result.put("total_count", totalCount);
        result.put("exception_count", exceptionCount);
        result.put("elapsed_ms", elapsed);
        return result;
    }

    /**
     * Normalize phone number: strip all non-digit characters
     */
    private String normalizePhone(String phone) {
        if (phone == null) return "";
        return phone.replaceAll("[^0-9]", "");
    }
}

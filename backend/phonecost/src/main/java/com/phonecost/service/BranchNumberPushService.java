package com.phonecost.service;

import com.phonecost.domain.AllocationOrgBatch;
import com.phonecost.domain.PhoneOwnershipEntry;
import com.phonecost.domain.SysOrganization;
import com.phonecost.repository.AllocationOrgBatchRepository;
import com.phonecost.repository.AllocationOrgEntryRepository;
import com.phonecost.repository.PhoneOwnershipEntryRepository;
import com.phonecost.repository.SysOrganizationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分行号码 → 号码分摊机构 推送服务
 *
 * 推送逻辑：
 * 1. 读取指定月份（sourceMonth）的 phone_ownership_entry 非例外数据
 * 2. 获取目标月份已存在的号码集合，已存在的号码跳过，不存在的号码新增
 * 3. 从目标月份之前最近一个有数据的月份的 allocation_org_entry 中匹配
 *    alloc_dept（分摊部门）、org_code（机构代码）、cost_center（成本中心）、remark（备注）
 * 4. 先软删除目标月份旧的 PUSH-BRN- 批次数据（旧前缀会归入差异推送 Tab）
 * 5. 创建 BRN- 推送批次，仅写入新增号码（不以 PUSH- 开头，数据归入号码分摊机构 Tab）
 */
@Slf4j
@Service
public class BranchNumberPushService {

    private final PhoneOwnershipEntryRepository ownershipEntryRepo;
    private final AllocationOrgBatchRepository allocationOrgBatchRepo;
    private final AllocationOrgEntryRepository allocationOrgEntryRepo;
    private final SysOrganizationRepository sysOrganizationRepo;
    private final DataScopeService dataScopeService;
    private final JdbcTemplate jdbcTemplate;

    public BranchNumberPushService(PhoneOwnershipEntryRepository ownershipEntryRepo,
                                   AllocationOrgBatchRepository allocationOrgBatchRepo,
                                   AllocationOrgEntryRepository allocationOrgEntryRepo,
                                   SysOrganizationRepository sysOrganizationRepo,
                                   DataScopeService dataScopeService,
                                   JdbcTemplate jdbcTemplate) {
        this.ownershipEntryRepo = ownershipEntryRepo;
        this.allocationOrgBatchRepo = allocationOrgBatchRepo;
        this.allocationOrgEntryRepo = allocationOrgEntryRepo;
        this.sysOrganizationRepo = sysOrganizationRepo;
        this.dataScopeService = dataScopeService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 推送分行号码数据到号码分摊机构
     *
     * 已存在的号码跳过，不存在的号码新增。
     * 分摊部门、机构代码、成本中心、备注 从最近月清单获取。
     *
     * @param sourceMonth 分行号码月份（数据来源）
     * @param targetMonth 推送目标月份（写入号码分摊机构的月份，可为空则取 sourceMonth）
     * @param userId      当前操作用户
     * @return 推送结果统计
     */
    @Transactional
    public Map<String, Object> pushFromBranchNumber(String sourceMonth, String targetMonth, Long userId) {
        if (sourceMonth == null || sourceMonth.isBlank()) {
            throw new IllegalArgumentException("推送需要提供分行号码月份 sourceMonth");
        }

        // 1. 读取分行号码数据（指定月份）
        List<PhoneOwnershipEntry> sourceEntries = ownershipEntryRepo.findAllByBillingMonth(sourceMonth);
        if (sourceEntries == null || sourceEntries.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("pushed", 0);
            result.put("skipped", 0);
            result.put("message", "所选月份无分行号码数据可推送");
            return result;
        }
        // 数据权限隔离：非 admin/finance 仅能推送自己可见一级分行范围的号码
        Set<String> visibleBranchNames = resolveVisibleL1BranchNames(userId);
        if (visibleBranchNames != null) {
            sourceEntries = sourceEntries.stream()
                    .filter(e -> e.getL1Branch() != null && visibleBranchNames.contains(e.getL1Branch().trim()))
                    .collect(Collectors.toList());
            log.info("分行号码推送：用户 {} 数据范围可见 {} 个一级分行，过滤后源数据 {} 条",
                    userId, visibleBranchNames.size(), sourceEntries.size());
            if (sourceEntries.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("pushed", 0);
                result.put("skipped", 0);
                result.put("message", "当前用户数据范围内无可推送的分行号码数据");
                return result;
            }
        }
        // 过滤例外条目（is_exception=1 不推送，仅推送正常号码）
        sourceEntries = sourceEntries.stream()
                .filter(e -> e.getIsException() == null || e.getIsException() == 0)
                .collect(Collectors.toList());
        if (sourceEntries.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("pushed", 0);
            result.put("skipped", 0);
            result.put("message", "所选月份无可推送的正常号码数据（全部为例外）");
            return result;
        }
        // 去重：同号码取最新一条（按 id 降序）
        Map<String, PhoneOwnershipEntry> deduped = new LinkedHashMap<>();
        sourceEntries.stream()
                .filter(e -> e.getPhoneNumber() != null && !e.getPhoneNumber().isBlank())
                .sorted(Comparator.comparingLong(PhoneOwnershipEntry::getId).reversed())
                .forEach(e -> deduped.putIfAbsent(e.getPhoneNumber().trim(), e));
        List<PhoneOwnershipEntry> pushEntries = new ArrayList<>(deduped.values());
        if (pushEntries.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("pushed", 0);
            result.put("skipped", 0);
            result.put("message", "所选月份无可推送的号码数据");
            return result;
        }

        // 2. 目标月份
        String billingMonth = (targetMonth != null && !targetMonth.isBlank()) ? targetMonth : sourceMonth;

        // 3. 软删除目标月份旧的 PUSH-BRN- 批次数据（旧前缀会归入差异推送 Tab，需先清理）
        int cleanedOldPush = softDeleteOldPushBrnEntries(billingMonth);
        log.info("分行号码推送：目标月份 {} 软删除旧 PUSH-BRN- 数据 {} 条", billingMonth, cleanedOldPush);

        // 4. 获取目标月份已存在的号码集合（已存在的跳过，仅排除导入来源的正常数据）
        Set<String> existingPhones = getExistingPhones(billingMonth);
        log.info("分行号码推送：目标月份 {} 已存在 {} 个号码", billingMonth, existingPhones.size());

        // 5. 过滤出新增号码（已存在的跳过）
        List<PhoneOwnershipEntry> newEntries = pushEntries.stream()
                .filter(e -> !existingPhones.contains(e.getPhoneNumber().trim()))
                .collect(Collectors.toList());
        int skippedCount = pushEntries.size() - newEntries.size();
        log.info("分行号码推送：总共 {} 个号码，跳过 {} 个已存在，新增 {} 个",
                pushEntries.size(), skippedCount, newEntries.size());

        if (newEntries.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("pushed", 0);
            result.put("skipped", skippedCount);
            result.put("source_month", sourceMonth);
            result.put("target_month", billingMonth);
            result.put("cleaned_old_push", cleanedOldPush);
            result.put("message", "所有号码在目标月份已存在，跳过 " + skippedCount + " 条，无需推送");
            return result;
        }

        // 6. 查找最近的月清单（目标月份之前最近有数据的月份）
        String matchMonth = findLatestMonthBefore(billingMonth);
        log.info("分行号码推送：sourceMonth={}, targetMonth={}, matchMonth={}", sourceMonth, billingMonth, matchMonth);

        // 7. 构建匹配映射：phone_number → {alloc_dept, org_code, cost_center, remark}
        Map<String, String[]> matchMap = new HashMap<>();
        if (matchMonth != null) {
            // 数据权限隔离：匹配数据同样按用户可见一级分行范围过滤
            // allocation_org_entry 表的归属列为 branch_org_id（org id），需将可见分行名称转成 org id 集合
            List<Object> args = new ArrayList<>();
            args.add(matchMonth);
            String sql = "SELECT e.phone_number, e.alloc_dept, e.org_code, e.cost_center, e.remark " +
                    "FROM allocation_org_entry e " +
                    "INNER JOIN allocation_org_batch b ON e.batch_id = b.id " +
                    "WHERE b.billing_month = ? AND e.deleted_at IS NULL AND b.deleted_at IS NULL";
            if (visibleBranchNames != null && !visibleBranchNames.isEmpty()) {
                Map<String, Long> branchNameToOrgId = buildBranchNameMap();
                List<Long> visibleOrgIds = new ArrayList<>();
                for (String name : visibleBranchNames) {
                    Long orgId = branchNameToOrgId.get(name.trim());
                    if (orgId != null) {
                        visibleOrgIds.add(orgId);
                    }
                }
                if (!visibleOrgIds.isEmpty()) {
                    String placeholders = String.join(",", Collections.nCopies(visibleOrgIds.size(), "?"));
                    sql += " AND e.branch_org_id IN (" + placeholders + ") ";
                    args.addAll(visibleOrgIds);
                }
            }
            List<Map<String, Object>> matchRows = jdbcTemplate.queryForList(sql, args.toArray());
            for (Map<String, Object> row : matchRows) {
                putMatchRow(row, matchMap);
            }
        }
        log.info("分行号码推送：匹配月份 {} 共 {} 个号码可匹配", matchMonth, matchMap.size());

        // 8. 组装待插入数据（仅新增号码）
        List<Map<String, Object>> pushRows = new ArrayList<>();
        int matchedCount = 0, unmatchedCount = 0;
        for (PhoneOwnershipEntry e : newEntries) {
            String phone = e.getPhoneNumber().trim();
            String[] matched = matchMap.get(phone);
            Map<String, Object> row = new HashMap<>();
            row.put("phone_number", phone);
            row.put("username", e.getDescription() != null ? e.getDescription() : "");
            row.put("l1_branch", e.getL1Branch() != null ? e.getL1Branch() : "");
            String allocDept = "";
            String orgCode = "";
            String costCenter = "";
            String remark = "";
            String changeType;
            // 仅当匹配到的分摊部门非空才算「已匹配」；号码存在但分摊部门为空仍视为未匹配（置顶）
            if (matched != null && matched[0] != null && !matched[0].isBlank()) {
                allocDept = matched[0];
                orgCode = matched[1] != null ? matched[1] : "";
                costCenter = matched[2] != null ? matched[2] : "";
                remark = matched[3] != null ? matched[3] : "";
                changeType = "matched";
                matchedCount++;
            } else {
                changeType = "unmatched";
                unmatchedCount++;
            }
            row.put("alloc_dept", allocDept);
            row.put("org_code", orgCode);
            row.put("cost_center", costCenter);
            row.put("remark", remark);
            row.put("change_type", changeType);
            row.put("extension", e.getExtension() != null ? e.getExtension() : "");
            row.put("dept_path", e.getFullPath() != null ? e.getFullPath() : "");
            pushRows.add(row);
        }

        // 9. 创建推送批次
        Long branchOrgId = dataScopeService.resolveBranchOrgId(userId);
        String batchNo = "BRN-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        AllocationOrgBatch batch = AllocationOrgBatch.builder()
                .batchNo(batchNo)
                .fileName("分行号码推送_" + sourceMonth + "_to_" + billingMonth)
                .totalCount(pushRows.size())
                .billingMonth(billingMonth)
                .importStatus((byte) 1) // 直接标记完成
                .importedBy(userId)
                .branchOrgId(branchOrgId)
                .build();
        batch = allocationOrgBatchRepo.save(batch);

        // 10. 批量写入 allocation_org_entry
        Long batchId = batch.getId();
        Map<String, Long> branchNameToOrgId = buildBranchNameMap();
        String insertSql = "INSERT INTO allocation_org_entry (batch_id, phone_number, username, l1_branch, branch_org_id, alloc_dept, dept_path, extension, change_type, changed_columns, org_code, cost_center, remark, created_at, updated_at) " +
                           "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '', ?, ?, ?, NOW(), NOW())";
        jdbcTemplate.batchUpdate(insertSql, pushRows, pushRows.size(), (ps, row) -> {
            ps.setLong(1, batchId);
            ps.setString(2, String.valueOf(row.get("phone_number")));
            ps.setString(3, String.valueOf(row.get("username")));
            ps.setString(4, String.valueOf(row.get("l1_branch")));
            // branch_org_id：按 l1_branch 匹配 sys_organization(type=2)
            Long matchedBranchId = branchNameToOrgId.get(String.valueOf(row.get("l1_branch")).trim());
            if (matchedBranchId != null) {
                ps.setLong(5, matchedBranchId);
            } else {
                ps.setNull(5, java.sql.Types.BIGINT);
            }
            ps.setString(6, String.valueOf(row.get("alloc_dept")));
            ps.setString(7, String.valueOf(row.get("dept_path")));
            ps.setString(8, String.valueOf(row.get("extension")));
            ps.setString(9, String.valueOf(row.get("change_type")));
            ps.setString(10, String.valueOf(row.get("org_code")));
            ps.setString(11, String.valueOf(row.get("cost_center")));
            ps.setString(12, String.valueOf(row.get("remark")));
        });

        Map<String, Object> result = new HashMap<>();
        result.put("batch_id", batchId);
        result.put("batch_no", batchNo);
        result.put("pushed", pushRows.size());
        result.put("skipped", skippedCount);
        result.put("matched", matchedCount);
        result.put("unmatched", unmatchedCount);
        result.put("cleaned_old_push", cleanedOldPush);
        result.put("source_month", sourceMonth);
        result.put("target_month", billingMonth);
        result.put("match_month", matchMonth != null ? matchMonth : "");
        result.put("message", "成功推送 " + pushRows.size() + " 条新号码（匹配 " + matchedCount + " 条，未匹配 "
                + unmatchedCount + " 条，跳过已存在 " + skippedCount + " 条）");
        return result;
    }

    /**
     * 将匹配结果行写入 matchMap
     */
    private void putMatchRow(Map<String, Object> row, Map<String, String[]> matchMap) {
        Object phone = row.get("phone_number");
        if (phone != null && !String.valueOf(phone).isBlank()) {
            String allocDept = row.get("alloc_dept") != null ? String.valueOf(row.get("alloc_dept")) : "";
            String orgCode = row.get("org_code") != null ? String.valueOf(row.get("org_code")) : "";
            String costCenter = row.get("cost_center") != null ? String.valueOf(row.get("cost_center")) : "";
            String remark = row.get("remark") != null ? String.valueOf(row.get("remark")) : "";
            matchMap.putIfAbsent(String.valueOf(phone).trim(), new String[]{allocDept, orgCode, costCenter, remark});
        }
    }

    /**
     * 解析用户可见一级分行（type=2）名称集合。
     * admin/finance 返回 null（全量可见）；分行/部门返回其可见范围对应的 l1_branch 名称。
     */
    private Set<String> resolveVisibleL1BranchNames(Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        if (scope.isAllScope()) return null;
        List<Long> visibleOrgIds = scope.getVisibleOrgIds();
        if (visibleOrgIds == null || visibleOrgIds.isEmpty()) {
            // 回退：单 org 沿 path 向上找一级分行
            if (scope.getSingleOrgId() != null) {
                return resolveBranchNameFromPath(scope.getSingleOrgId());
            }
            return Set.of();
        }
        Map<Long, SysOrganization> orgMap = sysOrganizationRepo.findByDeletedAtIsNull().stream()
                .collect(java.util.stream.Collectors.toMap(SysOrganization::getId, o -> o, (a, b) -> a));
        Set<String> names = new HashSet<>();
        for (Long orgId : visibleOrgIds) {
            SysOrganization org = orgMap.get(orgId);
            if (org != null && org.getType() != null && org.getType() == 2) {
                names.add(org.getName());
            }
        }
        // 从 path 解析一级分行名
        if (scope.getSingleOrgId() != null) {
            names.addAll(resolveBranchNameFromPath(scope.getSingleOrgId()));
        }
        return names;
    }

    /**
     * 从组织 path 向上解析最近的一级分行（type=2）名称
     */
    private Set<String> resolveBranchNameFromPath(Long orgId) {
        Set<String> names = new HashSet<>();
        if (orgId == null) return names;
        Map<Long, SysOrganization> orgMap = sysOrganizationRepo.findByDeletedAtIsNull().stream()
                .collect(java.util.stream.Collectors.toMap(SysOrganization::getId, o -> o, (a, b) -> a));
        SysOrganization org = orgMap.get(orgId);
        if (org != null && org.getPath() != null) {
            String[] segments = org.getPath().split("/");
            for (int i = segments.length - 1; i >= 0; i--) {
                if (segments[i].isEmpty()) continue;
                SysOrganization ancestor = orgMap.get(Long.parseLong(segments[i]));
                if (ancestor != null && ancestor.getType() != null && ancestor.getType() == 2) {
                    names.add(ancestor.getName());
                    break;
                }
            }
        }
        return names;
    }

    /**
     * 软删除目标月份旧的 PUSH-BRN- 批次数据
     * 旧代码用 PUSH-BRN- 前缀，数据被归入差异推送 Tab；新代码用 BRN- 前缀，需先清理旧数据
     */
    private int softDeleteOldPushBrnEntries(String billingMonth) {
        // 软删除 entry
        int entryCount = jdbcTemplate.update(
                "UPDATE allocation_org_entry e INNER JOIN allocation_org_batch b ON e.batch_id = b.id " +
                "SET e.deleted_at = NOW() " +
                "WHERE b.billing_month = ? AND b.batch_no LIKE 'PUSH-BRN-%' AND e.deleted_at IS NULL AND b.deleted_at IS NULL",
                billingMonth);
        // 软删除 batch
        jdbcTemplate.update(
                "UPDATE allocation_org_batch SET deleted_at = NOW() " +
                "WHERE billing_month = ? AND batch_no LIKE 'PUSH-BRN-%' AND deleted_at IS NULL",
                billingMonth);
        return entryCount;
    }

    /**
     * 获取目标月份已存在的号码集合（用于跳过已存在号码）
     */
    private Set<String> getExistingPhones(String billingMonth) {
        if (billingMonth == null || billingMonth.isBlank()) {
            return Collections.emptySet();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT DISTINCT e.phone_number " +
                "FROM allocation_org_entry e " +
                "INNER JOIN allocation_org_batch b ON e.batch_id = b.id " +
                "WHERE b.billing_month = ? AND e.deleted_at IS NULL AND b.deleted_at IS NULL",
                billingMonth);
        Set<String> phones = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Object phone = row.get("phone_number");
            if (phone != null && !String.valueOf(phone).isBlank()) {
                phones.add(String.valueOf(phone).trim());
            }
        }
        return phones;
    }

    /**
     * 查找 billingMonth 之前的最近一个有号码分摊机构数据的月份
     * 用于「从最近的月清单获取分摊部门等字段」
     */
    private String findLatestMonthBefore(String billingMonth) {
        List<String> months = allocationOrgBatchRepo.findDistinctBillingMonths();
        if (months == null || months.isEmpty()) return null;
        // months 已按 billing_month DESC 排序（见 Repository）
        for (String m : months) {
            if (m != null && m.compareTo(billingMonth) < 0) {
                return m;
            }
        }
        return null;
    }

    /**
     * 构建分行名称→orgId 映射（type=2 的所有一级分行）
     */
    private Map<String, Long> buildBranchNameMap() {
        List<SysOrganization> branches = sysOrganizationRepo.findByTypeAndDeletedAtIsNull((byte) 2);
        Map<String, Long> map = new HashMap<>();
        for (SysOrganization org : branches) {
            if (org.getName() != null) {
                map.put(org.getName().trim(), org.getId());
            }
        }
        return map;
    }
}

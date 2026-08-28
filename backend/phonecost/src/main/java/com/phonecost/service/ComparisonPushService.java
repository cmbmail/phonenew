package com.phonecost.service;

import com.phonecost.domain.*;
import com.phonecost.repository.AllocationOrgBatchRepository;
import com.phonecost.repository.AllocationOrgEntryRepository;
import com.phonecost.repository.DirectoryBatchRepository;
import com.phonecost.repository.DirectoryEntryRepository;
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
 * 数据对比推送服务：将差异数据推送到号码分摊机构
 *
 * 推送逻辑：
 * 1. 通讯录差异：取 added + changed 类型记录，以 phone_number 为匹配键写入 allocation_org_entry
 *    - phone_number → phone_number
 *    - dept_path 中一级分行名 → l1_branch（提取 "-" 分隔的第一段分行名，如 "广州分行"）
 *    - dept_path 剩余部分 → alloc_dept
 * 2. 例外数据差异：取有差异的例外条目，同样提取写入
 *
 * 每次推送创建一个独立批次，billing_month 取待核对月份（month2）。
 */
@Slf4j
@Service
public class ComparisonPushService {

    private final DirectoryEntryRepository directoryEntryRepository;
    private final DirectoryBatchRepository directoryBatchRepository;
    private final AllocationOrgBatchRepository allocationOrgBatchRepository;
    private final AllocationOrgEntryRepository allocationOrgEntryRepository;
    private final SysOrganizationRepository sysOrganizationRepository;
    private final DataScopeService dataScopeService;
    private final JdbcTemplate jdbcTemplate;

    public ComparisonPushService(DirectoryEntryRepository directoryEntryRepository,
                                  DirectoryBatchRepository directoryBatchRepository,
                                  AllocationOrgBatchRepository allocationOrgBatchRepository,
                                  AllocationOrgEntryRepository allocationOrgEntryRepository,
                                  SysOrganizationRepository sysOrganizationRepository,
                                  DataScopeService dataScopeService,
                                  JdbcTemplate jdbcTemplate) {
        this.directoryEntryRepository = directoryEntryRepository;
        this.directoryBatchRepository = directoryBatchRepository;
        this.allocationOrgBatchRepository = allocationOrgBatchRepository;
        this.allocationOrgEntryRepository = allocationOrgEntryRepository;
        this.sysOrganizationRepository = sysOrganizationRepository;
        this.dataScopeService = dataScopeService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 推送通讯录差异到号码分摊机构
     *
     * @param month1 参考月份
     * @param month2 待核对月份（推送数据的 billing_month）
     * @param types  推送类型过滤（added,changed,removed），为空则默认 added+changed
     * @param userId 当前操作用户
     * @return 推送结果统计
     */
    @Transactional
    public Map<String, Object> pushDirectoryComparison(String month1, String month2,
                                                        Set<String> types, Long userId) {
        // 1. 构建差异数据
        Map<String, Object> full = buildDirectoryCompareFull(month1, month2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allDiffs = (List<Map<String, Object>>) full.get("diffs");

        // 2. 筛选推送类型（默认只推送 added + changed）
        Set<String> pushTypes = (types != null && !types.isEmpty()) ? types : Set.of("added", "changed");
        List<Map<String, Object>> pushDiffs = allDiffs.stream()
                .filter(d -> pushTypes.contains(String.valueOf(d.get("type"))))
                .collect(Collectors.toList());

        if (pushDiffs.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("pushed", 0);
            result.put("skipped", allDiffs.size());
            result.put("message", "无符合条件的数据需要推送");
            return result;
        }

        // 3. 软删除同月份同号码的旧推送数据（避免重复推送产生重复项）
        List<String> phoneNumbers = pushDiffs.stream()
                .map(d -> safeStr(d.get("phone_number")))
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        int deletedCount = softDeleteOldPushEntries(month2, phoneNumbers);
        log.info("推送通讯录差异：软删除 {} 条同月份同号码旧推送数据 (month={})", deletedCount, month2);

        // 4. 创建推送批次
        Long branchOrgId = dataScopeService.resolveBranchOrgId(userId);
        String batchNo = "PUSH-COMP-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        AllocationOrgBatch batch = AllocationOrgBatch.builder()
                .batchNo(batchNo)
                .fileName("数据对比推送_" + month1 + "_vs_" + month2)
                .totalCount(pushDiffs.size())
                .billingMonth(month2)
                .importStatus((byte) 1) // 直接标记完成
                .importedBy(userId)
                .branchOrgId(branchOrgId)
                .build();
        batch = allocationOrgBatchRepository.save(batch);

        // 5. 写入 allocation_org_entry（逐条按 l1_branch 匹配分行 orgId 写入 branch_org_id）
        Long batchId = batch.getId();
        Map<String, Long> branchNameToOrgId = buildBranchNameMap();
        String insertSql = "INSERT INTO allocation_org_entry (batch_id, phone_number, username, l1_branch, branch_org_id, alloc_dept, dept_path, extension, change_type, changed_columns, org_code, cost_center, remark, created_at, updated_at) " +
                           "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";

        jdbcTemplate.batchUpdate(insertSql, pushDiffs, pushDiffs.size(), (ps, diff) -> {
            ps.setLong(1, batchId);
            ps.setString(2, safeStr(diff.get("phone_number")));
            ps.setString(3, safeStr(diff.get("username")));
            // 解析 dept_path 提取一级分行和分摊部门
            String deptPath = safeStr(diff.get("dept_path"));
            String[] parsed = parseDeptPath(deptPath);
            ps.setString(4, parsed[0]); // l1_branch
            // 按 l1_branch 名称匹配分行 orgId，未匹配时设为 NULL
            Long branchId = branchNameToOrgId.get(parsed[0]);
            if (branchId != null) {
                ps.setLong(5, branchId);
            } else {
                ps.setNull(5, java.sql.Types.BIGINT);
            }
            ps.setString(6, parsed[1]); // alloc_dept
            ps.setString(7, deptPath); // dept_path（原始部门全路径）
            ps.setString(8, safeStr(diff.get("extension")));
            String changeType = safeStr(diff.get("type"));
            ps.setString(9, changeType); // change_type
            // changed_columns: JSON 数组转逗号分隔字符串
            Object changedCols = diff.get("changed_columns");
            String changedColsStr = changedCols != null ? String.join(",", ((List<?>) changedCols).stream().map(String::valueOf).toArray(String[]::new)) : "";
            ps.setString(10, changedColsStr); // changed_columns
            ps.setString(11, "");        // org_code（推送时不填，由分行后续维护）
            ps.setString(12, "");        // cost_center（同上）
            ps.setString(13, "数据对比推送(" + changeType + ")"); // remark 标注来源
        });

        Map<String, Object> result = new HashMap<>();
        result.put("batch_id", batchId);
        result.put("batch_no", batchNo);
        result.put("pushed", pushDiffs.size());
        result.put("skipped", allDiffs.size() - pushDiffs.size());
        result.put("billing_month", month2);
        result.put("message", "成功推送 " + pushDiffs.size() + " 条差异数据到号码分摊机构");
        return result;
    }

    /**
     * 推送例外数据差异到号码分摊机构
     *
     * @param month  通讯录月份（空则取最新）
     * @param userId 当前操作用户
     * @return 推送结果统计
     */
    @Transactional
    public Map<String, Object> pushExceptionComparison(String month, Long userId) {
        // 1. 构建例外差异数据
        Map<String, Object> full = buildExceptionCompareFull(true, month);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) full.get("entries");

        // 只推送有差异的条目
        List<Map<String, Object>> diffEntries = entries.stream()
                .filter(e -> Boolean.TRUE.equals(e.get("has_diff")))
                .collect(Collectors.toList());

        if (diffEntries.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("pushed", 0);
            result.put("skipped", entries.size());
            result.put("message", "无有差异的例外数据需要推送");
            return result;
        }

        // 2. 软删除同月份同号码的旧推送数据（避免重复推送产生重复项）
        String billingMonth = (String) full.getOrDefault("billing_month", month != null ? month : "");
        List<String> phoneNumbers = diffEntries.stream()
                .map(e -> {
                    String p = safeStr(e.get("latest_phone_number"));
                    return p.isEmpty() ? safeStr(e.get("phone_number")) : p;
                })
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        int deletedCount = softDeleteOldPushEntries(billingMonth, phoneNumbers);
        log.info("推送例外差异：软删除 {} 条同月份同号码旧推送数据 (month={})", deletedCount, billingMonth);

        // 3. 创建推送批次
        Long branchOrgId = dataScopeService.resolveBranchOrgId(userId);
        String batchNo = "PUSH-EXC-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        AllocationOrgBatch batch = AllocationOrgBatch.builder()
                .batchNo(batchNo)
                .fileName("例外数据差异推送_" + billingMonth)
                .totalCount(diffEntries.size())
                .billingMonth(billingMonth)
                .importStatus((byte) 1)
                .importedBy(userId)
                .branchOrgId(branchOrgId)
                .build();
        batch = allocationOrgBatchRepository.save(batch);

        // 4. 写入 allocation_org_entry（逐条按 l1_branch 匹配分行 orgId 写入 branch_org_id）
        Long batchId = batch.getId();
        Map<String, Long> branchNameToOrgId = buildBranchNameMap();
        String insertSql = "INSERT INTO allocation_org_entry (batch_id, phone_number, username, l1_branch, branch_org_id, alloc_dept, dept_path, extension, change_type, changed_columns, org_code, cost_center, remark, created_at, updated_at) " +
                           "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";

        jdbcTemplate.batchUpdate(insertSql, diffEntries, diffEntries.size(), (ps, entry) -> {
            ps.setLong(1, batchId);
            // 例外数据中优先使用最新值，若无则用当前值
            String phone = safeStr(entry.get("latest_phone_number"));
            if (phone.isEmpty()) phone = safeStr(entry.get("phone_number"));
            ps.setString(2, phone);

            // username：优先最新值
            String username = safeStr(entry.get("latest_username"));
            if (username.isEmpty()) username = safeStr(entry.get("username"));
            ps.setString(3, username);

            String deptPath = safeStr(entry.get("latest_dept_path"));
            if (deptPath.isEmpty()) deptPath = safeStr(entry.get("dept_path"));
            String[] parsed = parseDeptPath(deptPath);
            ps.setString(4, parsed[0]); // l1_branch
            // 按 l1_branch 名称匹配分行 orgId，未匹配时设为 NULL
            Long branchId = branchNameToOrgId.get(parsed[0]);
            if (branchId != null) {
                ps.setLong(5, branchId);
            } else {
                ps.setNull(5, java.sql.Types.BIGINT);
            }
            ps.setString(6, parsed[1]); // alloc_dept
            ps.setString(7, deptPath); // dept_path（原始部门全路径）
            ps.setString(8, safeStr(entry.get("extension")));
            ps.setString(9, "exception"); // change_type
            // changed_columns
            Object changedCols = entry.get("changed_columns");
            String changedColsStr = changedCols != null ? String.join(",", ((List<?>) changedCols).stream().map(String::valueOf).toArray(String[]::new)) : "";
            ps.setString(10, changedColsStr);
            ps.setString(11, "");        // org_code
            ps.setString(12, "");        // cost_center
            ps.setString(13, "例外数据差异推送"); // remark
        });

        Map<String, Object> result = new HashMap<>();
        result.put("batch_id", batchId);
        result.put("batch_no", batchNo);
        result.put("pushed", diffEntries.size());
        result.put("skipped", entries.size() - diffEntries.size());
        result.put("billing_month", billingMonth);
        result.put("message", "成功推送 " + diffEntries.size() + " 条例外差异数据到号码分摊机构");
        return result;
    }

    // ==================== 对比逻辑（从 DataImportController 移植） ====================

    private Map<String, Object> buildDirectoryCompareFull(String month1, String month2) {
        List<DirectoryEntry> entries1 = directoryEntryRepository.findByBillingMonth(month1);
        List<DirectoryEntry> entries2 = directoryEntryRepository.findByBillingMonth(month2);

        java.util.function.Function<DirectoryEntry, String> extKeyFn = e ->
                e.getExtension() != null ? e.getExtension() : "";

        Map<String, DirectoryEntry> map1 = new java.util.LinkedHashMap<>();
        for (DirectoryEntry e : entries1) {
            String key = extKeyFn.apply(e);
            if (!key.isEmpty()) map1.putIfAbsent(key, e);
        }

        Map<String, DirectoryEntry> map2 = new java.util.LinkedHashMap<>();
        for (DirectoryEntry e : entries2) {
            String key = extKeyFn.apply(e);
            if (!key.isEmpty()) map2.putIfAbsent(key, e);
        }

        List<Map<String, Object>> diffs = new ArrayList<>();
        int added = 0, removed = 0, changed = 0, unchanged = 0;

        for (Map.Entry<String, DirectoryEntry> e2 : map2.entrySet()) {
            if (!map1.containsKey(e2.getKey())) {
                DirectoryEntry entry = e2.getValue();
                Map<String, Object> d = new HashMap<>();
                d.put("type", "added");
                d.put("username", entry.getUsername() != null ? entry.getUsername() : "");
                d.put("phone_number", entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");
                d.put("dept_path", entry.getDeptPath() != null ? entry.getDeptPath() : "");
                d.put("extension", entry.getExtension() != null ? entry.getExtension() : "");
                d.put("changed_columns", List.of("用户名称", "号码", "部门全路径"));
                diffs.add(d);
                added++;
            }
        }

        for (Map.Entry<String, DirectoryEntry> e1 : map1.entrySet()) {
            if (!map2.containsKey(e1.getKey())) {
                DirectoryEntry entry = e1.getValue();
                Map<String, Object> d = new HashMap<>();
                d.put("type", "removed");
                d.put("username", entry.getUsername() != null ? entry.getUsername() : "");
                d.put("phone_number", entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");
                d.put("dept_path", entry.getDeptPath() != null ? entry.getDeptPath() : "");
                d.put("extension", entry.getExtension() != null ? entry.getExtension() : "");
                d.put("changed_columns", List.of("用户名称", "号码", "部门全路径"));
                diffs.add(d);
                removed++;
            }
        }

        for (Map.Entry<String, DirectoryEntry> e1 : map1.entrySet()) {
            DirectoryEntry entry1 = e1.getValue();
            DirectoryEntry entry2 = map2.get(e1.getKey());
            if (entry2 == null) continue;

            String un2 = entry2.getUsername() != null ? entry2.getUsername() : "";
            String pn2 = entry2.getPhoneNumber() != null ? entry2.getPhoneNumber() : "";
            String dp2 = entry2.getDeptPath() != null ? entry2.getDeptPath() : "";
            String un1 = entry1.getUsername() != null ? entry1.getUsername() : "";
            String pn1 = entry1.getPhoneNumber() != null ? entry1.getPhoneNumber() : "";
            String dp1 = entry1.getDeptPath() != null ? entry1.getDeptPath() : "";

            List<String> changedCols = new ArrayList<>();
            if (!un1.equals(un2)) changedCols.add("用户名称");
            if (!pn1.equals(pn2)) changedCols.add("号码");
            if (!dp1.equals(dp2)) changedCols.add("部门全路径");

            if (!changedCols.isEmpty()) {
                Map<String, Object> d = new HashMap<>();
                d.put("type", "changed");
                d.put("username", un2);
                d.put("phone_number", pn2);
                d.put("dept_path", dp2);
                d.put("extension", entry2.getExtension() != null ? entry2.getExtension() : "");
                d.put("changed_columns", changedCols);
                diffs.add(d);
                changed++;
            } else {
                unchanged++;
            }
        }

        Map<String, Integer> typeOrder = Map.of("changed", 0, "added", 1, "removed", 2);
        diffs.sort((a, b) -> Integer.compare(
                typeOrder.getOrDefault(a.get("type"), 99),
                typeOrder.getOrDefault(b.get("type"), 99)));

        Map<String, Object> result = new HashMap<>();
        result.put("diffs", diffs);
        result.put("month1", month1);
        result.put("month2", month2);
        result.put("added", added);
        result.put("removed", removed);
        result.put("changed", changed);
        result.put("unchanged", unchanged);
        result.put("total", diffs.size());
        return result;
    }

    private Map<String, Object> buildExceptionCompareFull(boolean onlyDiff, String month) {
        String compareMonth;
        if (month != null && !month.isBlank()) {
            compareMonth = month;
        } else {
            List<String> months = directoryBatchRepository.findDistinctMonths();
            if (months.isEmpty()) {
                Map<String, Object> empty = new HashMap<>();
                empty.put("entries", List.of());
                empty.put("total", 0);
                empty.put("total_all", 0);
                empty.put("changed", 0);
                empty.put("unchanged", 0);
                empty.put("billing_month", "");
                return empty;
            }
            compareMonth = months.get(0);
        }

        List<DirectoryEntry> latestEntries = directoryEntryRepository.findByBillingMonth(compareMonth);
        Map<String, DirectoryEntry> latestMap = new java.util.LinkedHashMap<>();
        for (DirectoryEntry e : latestEntries) {
            String key = e.getExtension() != null ? e.getExtension() : "";
            if (!key.isEmpty()) latestMap.putIfAbsent(key, e);
        }

        List<DirectoryEntry> exceptionEntries = directoryEntryRepository.findExceptionEntriesAll();
        List<Map<String, Object>> results = new ArrayList<>();
        int changed = 0, unchanged = 0;

        for (DirectoryEntry exc : exceptionEntries) {
            String ext = exc.getExtension() != null ? exc.getExtension() : "";
            Map<String, Object> item = new HashMap<>();
            item.put("id", exc.getId());
            item.put("phone_number", exc.getPhoneNumber() != null ? exc.getPhoneNumber() : "");
            item.put("dept_path", exc.getDeptPath() != null ? exc.getDeptPath() : "");
            item.put("username", exc.getUsername() != null ? exc.getUsername() : "");
            item.put("extension", ext);
            item.put("seconded_keyword", exc.getSecondedKeyword() != null ? exc.getSecondedKeyword() : "");

            DirectoryEntry latest = latestMap.get(ext);
            List<String> changedCols = new ArrayList<>();

            if (latest != null) {
                String excDp = exc.getDeptPath() != null ? exc.getDeptPath() : "";
                String latDp = latest.getDeptPath() != null ? latest.getDeptPath() : "";
                String excUn = exc.getUsername() != null ? exc.getUsername() : "";
                String latUn = latest.getUsername() != null ? latest.getUsername() : "";
                String excPn = exc.getPhoneNumber() != null ? exc.getPhoneNumber() : "";
                String latPn = latest.getPhoneNumber() != null ? latest.getPhoneNumber() : "";

                if (!excUn.equals(latUn)) changedCols.add("用户名称");
                if (!excPn.equals(latPn)) changedCols.add("号码");
                if (!excDp.equals(latDp)) changedCols.add("部门全路径");

                item.put("latest_dept_path", latDp);
                item.put("latest_username", latUn);
                item.put("latest_phone_number", latPn);
            } else {
                changedCols.add("最新通讯录未找到");
                item.put("latest_dept_path", "");
                item.put("latest_username", "");
                item.put("latest_phone_number", "");
            }

            item.put("changed_columns", changedCols);
            item.put("has_diff", !changedCols.isEmpty());

            if (!changedCols.isEmpty()) changed++;
            else unchanged++;

            results.add(item);
        }

        int totalAll = results.size();
        List<Map<String, Object>> entries = onlyDiff
                ? results.stream().filter(r -> (boolean) r.get("has_diff")).collect(Collectors.toList())
                : results;

        Map<String, Object> result = new HashMap<>();
        result.put("entries", entries);
        result.put("total", entries.size());
        result.put("total_all", totalAll);
        result.put("changed", changed);
        result.put("unchanged", unchanged);
        result.put("billing_month", compareMonth);
        return result;
    }

    // ==================== 工具方法 ====================

    /**
     * 软删除同月份同号码的旧推送数据（PUSH- 开头批次的 entry）
     * 避免重复推送时产生重复项，新推送数据覆盖旧数据
     *
     * @param billingMonth 账单月份
     * @param phoneNumbers 要覆盖的号码列表
     * @return 被软删除的条目数
     */
    private int softDeleteOldPushEntries(String billingMonth, List<String> phoneNumbers) {
        if (phoneNumbers == null || phoneNumbers.isEmpty() || billingMonth == null || billingMonth.isBlank()) {
            return 0;
        }
        // 构建 IN 占位符
        String placeholders = String.join(",", Collections.nCopies(phoneNumbers.size(), "?"));
        String sql = "UPDATE allocation_org_entry e " +
                "INNER JOIN allocation_org_batch b ON e.batch_id = b.id " +
                "SET e.deleted_at = NOW() " +
                "WHERE e.deleted_at IS NULL " +
                "AND b.deleted_at IS NULL " +
                "AND b.batch_no LIKE 'PUSH-%' " +
                "AND e.phone_number IN (" + placeholders + ") " +
                "AND e.l1_branch IN (" +
                "  SELECT s.name FROM sys_organization s WHERE s.type = 2 AND s.deleted_at IS NULL" +
                ")"; // 保险：只删同号码的旧推送数据
        // 使用 billingMonth 过滤 batch
        // 实际上通过 phone_number + PUSH-% 已经足够精确，billingMonth 用于额外过滤
        // 但为避免误删其他月份数据，加入 billing_month 条件
        sql = "UPDATE allocation_org_entry e " +
                "INNER JOIN allocation_org_batch b ON e.batch_id = b.id " +
                "SET e.deleted_at = NOW() " +
                "WHERE e.deleted_at IS NULL " +
                "AND b.deleted_at IS NULL " +
                "AND b.batch_no LIKE 'PUSH-%' " +
                "AND b.billing_month = ? " +
                "AND e.phone_number IN (" + placeholders + ")";
        Object[] params = new Object[phoneNumbers.size() + 1];
        params[0] = billingMonth;
        for (int i = 0; i < phoneNumbers.size(); i++) {
            params[i + 1] = phoneNumbers.get(i);
        }
        return jdbcTemplate.update(sql, params);
    }

    /**
     * 构建分行名称→orgId 映射（type=2 的所有一级分行）
     * 用于推送时按 l1_branch 名称匹配分行 orgId，实现条目级数据隔离
     */
    private Map<String, Long> buildBranchNameMap() {
        List<SysOrganization> branches = sysOrganizationRepository.findByTypeAndDeletedAtIsNull((byte) 2);
        Map<String, Long> map = new HashMap<>();
        for (SysOrganization org : branches) {
            if (org.getName() != null) {
                map.put(org.getName().trim(), org.getId());
            }
        }
        return map;
    }

    /**
     * 解析部门全路径，提取一级分行名和分摊部门
     * dept_path 格式示例：100014-广州分行-100282-代管零售银行部
     * 提取规则：找到含"分行"的段作为一级分行名，其余非编码段拼接为分摊部门
     */
    private String[] parseDeptPath(String deptPath) {
        if (deptPath == null || deptPath.isBlank()) {
            return new String[]{"", ""};
        }
        String[] segments = deptPath.split("-");
        String l1Branch = "";
        List<String> deptParts = new ArrayList<>();
        boolean foundBranch = false;

        for (String seg : segments) {
            String trimmed = seg.trim();
            if (trimmed.isEmpty()) continue;
            // 含"分行"的段视为一级分行名
            if (!foundBranch && trimmed.contains("分行")) {
                l1Branch = trimmed;
                foundBranch = true;
            } else if (!isCodeSegment(trimmed)) {
                // 非编码段（编码段为纯数字，如 100014）
                deptParts.add(trimmed);
            }
        }

        String allocDept = String.join("-", deptParts);
        return new String[]{l1Branch, allocDept};
    }

    /** 判断是否为编码段（纯数字） */
    private boolean isCodeSegment(String seg) {
        return seg.matches("\\d+");
    }

    private String safeStr(Object val) {
        return val != null ? val.toString().trim() : "";
    }
}

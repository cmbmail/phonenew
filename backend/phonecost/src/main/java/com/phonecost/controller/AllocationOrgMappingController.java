package com.phonecost.controller;

import com.phonecost.domain.AllocationOrgMapping;
import com.phonecost.domain.SysOrganization;
import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.AllocationOrgMappingRepository;
import com.phonecost.repository.SysOrganizationRepository;
import com.phonecost.service.DataScopeService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 分摊机构对照表（业务规则）
 * 1) 每个部门全路径独占一条记录；同一一级分行下部门全路径唯一，同一部门全路径可出现在不同一级分行
 * 2) 同一一级分行下，机构名称对应的机构代码/成本中心代码须保持一致（同机构多部门行镜像同一值）。
 *    v1.12.153 起，导入/编辑中的代码/成本中心变更采用「机构级同步」语义：改任一行时自动同步
 *    该机构（同分行同名）全部行，不再拦截合法变更；同批次内相互矛盾（两行同机构不同值）仍拦截。
 *    不同机构名称可共用机构代码（v1.12.150）；成本中心可跨分行（v1.12.151）
 * 导入时不符合规则的数据逐行提示，不阻断其他行
 */
@RestController
@RequestMapping("/import/allocation-org-mapping")
@PreAuthorize("isAuthenticated()")
public class AllocationOrgMappingController {

    private static final String DEPT_SEPARATOR = "、";

    private final AllocationOrgMappingRepository repository;
    private final DataScopeService dataScopeService;
    private final SysOrganizationRepository orgRepository;

    public AllocationOrgMappingController(AllocationOrgMappingRepository repository,
                                         DataScopeService dataScopeService,
                                         SysOrganizationRepository orgRepository) {
        this.repository = repository;
        this.dataScopeService = dataScopeService;
        this.orgRepository = orgRepository;
    }

    /**
     * 数据隔离（v1.12.153）：admin/财务全量；分行/部门用户仅可写本行数据。
     * 返回 null 表示全量（无限制），非 null 为用户所属一级分行名称，写入行的 l1_branch 必须等于它。
     */
    private String resolveWritableBranchName(Long userId) {
        Long branchOrgId = dataScopeService.resolveBranchOrgId(userId);
        if (branchOrgId == null) {
            return null; // 未归属一级分行（admin/财务/总行）→ 全量
        }
        SysOrganization org = orgRepository.findByIdAndDeletedAtIsNull(branchOrgId).orElse(null);
        return org != null ? org.getName() : "\u0000未知名"; // 找不到组织名时用一个不可能匹配的值，拒绝写入
    }

    private void checkBranchWriteAccess(Long userId, String l1Branch) {
        String allowed = resolveWritableBranchName(userId);
        if (allowed != null && !allowed.equals(l1Branch)) {
            throw new RuntimeException("无权操作其他分行的数据（仅限 " + allowed + "）");
        }
    }

    // ==================== List (paginated + search) ====================

    @GetMapping("")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        size = Math.min(size, 200);
        var pageable = PageRequest.of(page, size);
        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? search.trim() : "";

        Page<AllocationOrgMapping> pageResult = hasSearch
                ? repository.searchByKeyword(escapeLikeKeyword(keyword), pageable)
                : repository.findByDeletedAtIsNull(pageable);

        List<Map<String, Object>> items = new ArrayList<>();
        for (AllocationOrgMapping m : pageResult.getContent()) {
            items.add(toMap(m));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", items);
        result.put("total", pageResult.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Create ====================

    @PostMapping("")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestBody Map<String, String> body,
            @RequestAttribute("userId") Long userId) {
        String orgName = body.getOrDefault("org_name", "").trim();
        if (orgName.isBlank()) {
            throw new RuntimeException("机构名称不能为空");
        }
        String orgCode = body.getOrDefault("org_code", "").trim();
        if (orgCode.isBlank()) {
            throw new RuntimeException("机构代码不能为空");
        }
        String costCenterCode = body.getOrDefault("cost_center_code", "").trim();
        String l1Branch = body.getOrDefault("l1_branch", "").trim();
        if (l1Branch.isBlank()) {
            throw new RuntimeException("一级分行不能为空");
        }
        String deptFullPath = body.getOrDefault("dept_full_path", "").trim();
        if (deptFullPath.isBlank()) {
            throw new RuntimeException("部门全路径不能为空");
        }
        if (deptFullPath.contains(DEPT_SEPARATOR)) {
            throw new RuntimeException("部门全路径需独占一行，不允许含「、」多值");
        }

        // 数据隔离：非 admin/总行用户仅可写本行数据（v1.12.153）
        checkBranchWriteAccess(userId, l1Branch);

        // 业务规则校验（不通过则报错）：部门同分行唯一；同机构库内值不一致时拒绝新增（避免扩大不一致）
        // 新增值与机构既有值不同时，机构级同步到该机构全部行（v1.12.153，与编辑语义一致）
        checkDeptUniqueness(l1Branch, deptFullPath, null);
        checkOrgInternalConsistency(l1Branch, orgName, null);
        syncOrgValues(l1Branch, orgName, orgCode, costCenterCode, null);

        AllocationOrgMapping m = new AllocationOrgMapping();
        m.setL1Branch(l1Branch);
        m.setOrgName(orgName);
        m.setOrgCode(orgCode);
        m.setCostCenterCode(nullableCost(costCenterCode));
        m.setDeptFullPath(deptFullPath);
        m.setRemark(body.getOrDefault("remark", ""));
        AllocationOrgMapping saved = repository.save(m);
        return ResponseEntity.ok(ApiResponse.ok(toMap(saved)));
    }

    // ==================== Update ====================

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestAttribute("userId") Long userId) {
        AllocationOrgMapping m = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new RuntimeException("记录不存在: " + id));

        String l1Branch = body.containsKey("l1_branch") && body.get("l1_branch") != null ? body.get("l1_branch").trim() : m.getL1Branch();
        String orgName = body.containsKey("org_name") && body.get("org_name") != null ? body.get("org_name").trim() : m.getOrgName();
        String orgCode = body.containsKey("org_code") && body.get("org_code") != null ? body.get("org_code").trim() : m.getOrgCode();
        String costCenterCode = body.containsKey("cost_center_code") && body.get("cost_center_code") != null ? body.get("cost_center_code").trim() : m.getCostCenterCode();
        String deptFullPath = body.containsKey("dept_full_path") && body.get("dept_full_path") != null
                ? body.get("dept_full_path").trim()
                : m.getDeptFullPath();

        if (l1Branch.isBlank()) {
            throw new RuntimeException("一级分行不能为空");
        }
        if (orgName.isBlank()) {
            throw new RuntimeException("机构名称不能为空");
        }
        if (orgCode.isBlank()) {
            throw new RuntimeException("机构代码不能为空");
        }
        if (deptFullPath != null && deptFullPath.contains(DEPT_SEPARATOR)) {
            throw new RuntimeException("部门全路径需独占一行，不允许含「、」多值");
        }

        // 数据隔离：非 admin/总行用户仅可写本行数据（含修改 l1_branch 字段的场景，v1.12.153）
        checkBranchWriteAccess(userId, m.getL1Branch());
        checkBranchWriteAccess(userId, l1Branch);

        // 业务规则校验（排除自身）：部门同分行唯一；同机构库内值不一致时拒绝修改（避免扩大不一致）；
        // 代码/成本中心变更采用机构级同步：自动同步到该机构（同分行同名）全部行（v1.12.153）
        checkDeptUniqueness(l1Branch, deptFullPath, id);
        checkOrgInternalConsistency(l1Branch, orgName, id);
        syncOrgValues(l1Branch, orgName, orgCode, costCenterCode, id);

        m.setL1Branch(l1Branch);
        m.setOrgName(orgName);
        m.setOrgCode(orgCode);
        m.setCostCenterCode(nullableCost(costCenterCode));
        m.setDeptFullPath(deptFullPath);
        if (body.containsKey("remark")) m.setRemark(body.get("remark"));
        AllocationOrgMapping saved = repository.save(m);
        return ResponseEntity.ok(ApiResponse.ok(toMap(saved)));
    }

    // ==================== Delete（物理删除：该表为可重导对照数据，且组合唯一索引覆盖行，软删会挡住后续导入） ====================

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        AllocationOrgMapping m = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new RuntimeException("记录不存在: " + id));
        repository.delete(m);
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("deleted", true);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Batch Delete ====================

    @PostMapping("/batch")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchDelete(
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long userId) {
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new RuntimeException("未选择任何记录");
        }
        List<Long> idList = ids.stream().map(Number::longValue).toList();
        List<AllocationOrgMapping> records = repository.findAllById(idList);
        List<AllocationOrgMapping> toDelete = new ArrayList<>();
        for (AllocationOrgMapping m : records) {
            if (m.getDeletedAt() == null) {
                toDelete.add(m);
            }
        }
        if (!toDelete.isEmpty()) {
            repository.deleteAllInBatch(toDelete);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("deleted", toDelete.size());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Import（全量覆盖模式，v1.12.154）====================
    // 导入后表内数据完全以文件为准：文件内未包含的旧记录自动删除；
    // 分行用户仅在其本行范围内执行覆盖（不影响他行数据）。

    @PostMapping("/import")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") Long userId) {
        int added = 0;
        int updated = 0;
        int deleted = 0;
        int skipped = 0;
        int orgSynced = 0;
        List<String> errors = new ArrayList<>();
        // 数据隔离：非 admin/总行用户仅可导入本行数据（v1.12.153）
        String allowedBranch = resolveWritableBranchName(userId);
        // 缓存当前批次已处理的记录（key：一级分行 + 部门全路径）
        Map<String, AllocationOrgMapping> deptCache = new HashMap<>();
        // 本批次同机构（分行+机构名）最终值基准：同批次内相互矛盾（两行同机构不同值）仍拦截。
        // 注意：不再预加载库内值作基准——库内旧值与导入值不同属合法变更（机构级同步，v1.12.153）
        Map<String, String[]> orgRef = new HashMap<>();
        // 规则占用表（均不含软删除行）：
        // (一级分行 + 部门) → 占用记录 id；同分行下部门全路径唯一（规则 1）
        Map<String, Long> deptOwner = new HashMap<>();
        // 本批次成功写入的记录 id（用于全量覆盖：文件外的旧记录删除，v1.12.154）
        Set<Long> importedIds = new java.util.HashSet<>();
        for (AllocationOrgMapping m : repository.findAllByDeletedAtIsNull()) {
            deptOwner.put(deptKey(m.getL1Branch(), m.getDeptFullPath()), m.getId());
        }
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String l1Branch = getCellString(row, 0);
                String orgName = getCellString(row, 1);
                String orgCode = getCellString(row, 2);
                String costCenterCode = getCellString(row, 3);
                String deptFullPath = getCellString(row, 4).trim();
                String remark = getCellString(row, 5);
                if (l1Branch.isBlank() && orgName.isBlank() && orgCode.isBlank()
                        && costCenterCode.isBlank() && deptFullPath.isBlank() && remark.isBlank()) continue;
                if (orgName.isBlank() || orgCode.isBlank() || l1Branch.isBlank()) {
                    skipped++;
                    errors.add("第" + (i + 1) + "行: 一级分行、机构名称、机构代码不能为空");
                    continue;
                }
                if (allowedBranch != null && !allowedBranch.equals(l1Branch)) {
                    skipped++;
                    errors.add("第" + (i + 1) + "行: 无权导入 " + l1Branch + " 的数据（仅限 " + allowedBranch + "）");
                    continue;
                }

                // 部门全路径独占一行：不允许单行多值（「、」为历史合并格式）
                if (deptFullPath.contains(DEPT_SEPARATOR)) {
                    skipped++;
                    errors.add("第" + (i + 1) + "行: 部门全路径需独占一行，不允许含「、」多值（请拆分为多行）");
                    continue;
                }
                if (deptFullPath.isBlank()) {
                    skipped++;
                    errors.add("第" + (i + 1) + "行: 部门全路径不能为空");
                    continue;
                }

                // upsert：按 一级分行+部门全路径 定位记录
                AllocationOrgMapping m = deptCache.get(deptKey(l1Branch, deptFullPath));
                if (m == null) {
                    m = repository.findByL1BranchAndDeptFullPathAndDeletedAtIsNull(l1Branch, deptFullPath)
                            .orElseGet(AllocationOrgMapping::new);
                }
                Long selfId = m.getId();

                // ===== 规则校验（不合规逐行提示，跳过不阻断）=====

                // 规则 1：部门全路径在同一一级分行内唯一（占用者为自身时放行，支持原记录更新自己的信息）
                Long owner = deptOwner.get(deptKey(l1Branch, deptFullPath));
                if (owner != null && !owner.equals(selfId)) {
                    skipped++;
                    errors.add("第" + (i + 1) + "行: 部门全路径 " + deptFullPath + " 在 " + l1Branch + " 下已存在");
                    continue;
                }

                // 规则 1b：命中既有记录（本批次或库内）时，机构名称必须一致——
                // 防止导入行改写其他机构的部门归属（部门独占一行，归属只随记录本身变化）
                if (selfId != null && !orgName.equals(m.getOrgName())) {
                    skipped++;
                    errors.add("第" + (i + 1) + "行: 部门全路径 " + deptFullPath + " 在 " + l1Branch
                            + " 下已归属机构「" + m.getOrgName() + "」，不能改为机构「" + orgName
                            + "」（如需调整请先删除原记录）");
                    continue;
                }

                // 规则 2（同批次内一致性）：同一分行同名机构的代码/成本中心在同批次内必须一致
                // （基准仅取本批次首条出现值；与库内旧值不同属合法变更，导入后自动机构级同步）
                String[] ref = orgRef.get(l1Branch + "|" + orgName);
                if (ref == null) {
                    orgRef.put(l1Branch + "|" + orgName, new String[]{orgCode, costCenterCode});
                } else {
                    if (!ref[0].equals(orgCode)) {
                        skipped++;
                        errors.add("第" + (i + 1) + "行: 机构名称 " + orgName + " 在本批次已对应机构代码 " + ref[0] + "，与本次 " + orgCode + " 不一致（同机构值须一致）");
                        continue;
                    }
                    String refCost = ref[1];
                    if (!refCost.equals(costCenterCode)) {
                        skipped++;
                        errors.add("第" + (i + 1) + "行: 机构名称 " + orgName + " 在本批次已对应成本中心 " + (refCost.isEmpty() ? "空" : refCost) + "，与本次 " + (costCenterCode.isEmpty() ? "空" : costCenterCode) + " 不一致（同机构值须一致）");
                        continue;
                    }
                }

                m.setL1Branch(l1Branch);
                m.setOrgName(orgName);
                m.setOrgCode(orgCode);
                m.setCostCenterCode(nullableCost(costCenterCode));
                m.setDeptFullPath(deptFullPath);
                m.setRemark(remark);
                boolean isNew = (selfId == null);
                repository.save(m);
                repository.flush();
                deptCache.put(deptKey(l1Branch, deptFullPath), m);
                importedIds.add(m.getId());
                // 登记部门占用（新增记录 id 已生成）
                deptOwner.put(deptKey(l1Branch, deptFullPath), m.getId());
                // 机构级同步：命中既有记录且代码/成本中心与本行不同时，同步该机构同分行其他行（v1.12.153）
                if (selfId != null) {
                    orgSynced += syncOrgValues(l1Branch, orgName, orgCode, costCenterCode, selfId);
                }
                if (isNew) {
                    added++;
                } else {
                    updated++;
                }
            }

            // 全量覆盖（v1.12.154）：文件外的旧记录自动删除——导入后表内数据完全以文件为准。
            // admin/总行（allowedBranch == null）删除全部未在文件中的记录；
            // 分行用户仅删除本行范围内未在文件中的记录（不能影响他行数据）。
            List<AllocationOrgMapping> toDelete = new ArrayList<>();
            for (AllocationOrgMapping m : repository.findAllByDeletedAtIsNull()) {
                if (importedIds.contains(m.getId())) continue;
                if (allowedBranch != null && !allowedBranch.equals(m.getL1Branch())) continue;
                toDelete.add(m);
            }
            if (!toDelete.isEmpty()) {
                repository.deleteAllInBatch(toDelete);
                deleted = toDelete.size();
            }
        } catch (IOException e) {
            throw new RuntimeException("导入失败: " + e.getMessage(), e);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            String rootMsg = e.getRootCause() != null ? e.getRootCause().getMessage() : e.getMessage();
            throw new RuntimeException("导入失败（数据完整性冲突）: " + rootMsg, e);
        } catch (Exception e) {
            throw new RuntimeException("导入失败: " + e.getMessage(), e);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("imported", added + updated);
        result.put("added", added);
        result.put("updated", updated);
        result.put("deleted", deleted);
        result.put("skipped", skipped);
        result.put("org_synced", orgSynced);
        result.put("errors", errors);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Export ====================

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportEntries() {
        List<AllocationOrgMapping> items = repository.findAllForExport();

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("分摊机构对照表");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"一级分行", "机构名称", "机构代码", "成本中心代码", "部门全路径", "备注"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (AllocationOrgMapping m : items) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(m.getL1Branch() != null ? m.getL1Branch() : "");
                row.createCell(1).setCellValue(m.getOrgName() != null ? m.getOrgName() : "");
                row.createCell(2).setCellValue(m.getOrgCode() != null ? m.getOrgCode() : "");
                row.createCell(3).setCellValue(m.getCostCenterCode() != null ? m.getCostCenterCode() : "");
                row.createCell(4).setCellValue(m.getDeptFullPath() != null ? m.getDeptFullPath() : "");
                row.createCell(5).setCellValue(m.getRemark() != null ? m.getRemark() : "");
            }

            wb.write(out);
            String fileName = URLEncoder.encode("分摊机构对照表导出.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出失败", e);
        }
    }

    // ==================== Template ====================

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("分摊机构对照表");
            Row headerRow = sheet.createRow(0);
            String[] headers = {"一级分行", "机构名称", "机构代码", "成本中心代码", "部门全路径", "备注"};
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
                sheet.setColumnWidth(i, 6000);
            }
            wb.write(out);
            String fileName = URLEncoder.encode("分摊机构对照表导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成模板失败", e);
        }
    }

    // ==================== Helpers ====================

    /**
     * 同机构库内值不一致检测（v1.12.153）：排除自身后，若同分行同名机构在库内存在不同的代码/成本中心，
     * 说明历史数据本身已经不一致，拒绝变更以避免不一致而扩大（提示用户先修复数据）
     */
    private void checkOrgInternalConsistency(String l1Branch, String orgName, Long excludeId) {
        String firstCode = null;
        String firstCost = null;
        boolean seen = false;
        for (AllocationOrgMapping m : repository.findAllByDeletedAtIsNull()) {
            if (excludeId != null && m.getId().equals(excludeId)) continue;
            if (!m.getL1Branch().equals(l1Branch) || !m.getOrgName().equals(orgName)) continue;
            String code = m.getOrgCode();
            String cost = m.getCostCenterCode() == null ? "" : m.getCostCenterCode();
            if (!seen) {
                firstCode = code;
                firstCost = cost;
                seen = true;
            } else {
                if (!firstCode.equals(code) || !firstCost.equals(cost)) {
                    throw new RuntimeException("机构「" + orgName + "」在 " + l1Branch
                            + " 下已存在多条代码/成本中心不一致的记录，请先修复数据或删除重导后再修改");
                }
            }
        }
    }

    /**
     * 机构级同步（v1.12.153）：将同分行同名机构（排除自身）的代码/成本中心同步为本行值。
     * 变更合法（机构级属性镜像语义），不再拦截；返回同步行数
     */
    private int syncOrgValues(String l1Branch, String orgName, String orgCode,
                              String costCenterCode, Long excludeId) {
        int synced = 0;
        for (AllocationOrgMapping m : repository.findAllByDeletedAtIsNull()) {
            if (excludeId != null && m.getId().equals(excludeId)) continue;
            if (!m.getL1Branch().equals(l1Branch) || !m.getOrgName().equals(orgName)) continue;
            String newCost = nullableCost(costCenterCode);
            if (!m.getOrgCode().equals(orgCode) || !Objects.equals(m.getCostCenterCode(), newCost)) {
                m.setOrgCode(orgCode);
                m.setCostCenterCode(newCost);
                synced++;
            }
        }
        return synced;
    }

    /** 统一空成本中心的落库值：空串存 NULL */
    private String nullableCost(String costCenterCode) {
        return costCenterCode == null || costCenterCode.isBlank() ? null : costCenterCode;
    }

    /** 部门全路径在同一一级分行内唯一校验（排除自身 id，不含软删除记录；部门独占一行无多值） */
    private void checkDeptUniqueness(String l1Branch, String deptFullPath, Long excludeId) {
        if (deptFullPath == null || deptFullPath.isBlank()) return;
        for (AllocationOrgMapping m : repository.findAllByDeletedAtIsNull()) {
            if (excludeId != null && m.getId().equals(excludeId)) continue;
            if (!m.getL1Branch().equals(l1Branch)) continue; // 不同一级分行允许相同部门全路径
            if (deptFullPath.equals(m.getDeptFullPath())) {
                throw new RuntimeException("部门全路径「" + deptFullPath + "」在 " + l1Branch + " 下已存在（机构: " + m.getOrgName() + "）");
            }
        }
    }

    private String deptKey(String l1Branch, String dept) {
        return l1Branch + "|" + dept;
    }

    /** LIKE 关键词转义：%、_、\\ 以字面量匹配（v1.12.153） */
    private String escapeLikeKeyword(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Map<String, Object> toMap(AllocationOrgMapping m) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", m.getId());
        map.put("l1_branch", m.getL1Branch() != null ? m.getL1Branch() : "");
        map.put("org_name", m.getOrgName() != null ? m.getOrgName() : "");
        map.put("org_code", m.getOrgCode() != null ? m.getOrgCode() : "");
        map.put("cost_center_code", m.getCostCenterCode() != null ? m.getCostCenterCode() : "");
        map.put("dept_full_path", m.getDeptFullPath() != null ? m.getDeptFullPath() : "");
        map.put("remark", m.getRemark() != null ? m.getRemark() : "");
        map.put("created_at", m.getCreatedAt() != null ? m.getCreatedAt().toString() : "");
        map.put("updated_at", m.getUpdatedAt() != null ? m.getUpdatedAt().toString() : "");
        return map;
    }

    private String getCellString(Row row, int idx) {
        Cell cell = row.getCell(idx);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v) && !Double.isInfinite(v)) yield String.valueOf((long) v);
                yield String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}

package com.phonecost.controller;

import com.phonecost.domain.AllocationOrgMapping;
import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.AllocationOrgMappingRepository;
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
 * 2) 同一一级分行下，机构名称对应的机构代码/成本中心代码一致（同机构多部门行校验）；不同机构名称可共用机构代码（v1.12.150）
 * 成本中心可跨分行（v1.12.151）
 * 导入时不符合规则的数据逐行提示，不阻断其他行
 */
@RestController
@RequestMapping("/import/allocation-org-mapping")
@PreAuthorize("isAuthenticated()")
public class AllocationOrgMappingController {

    private static final String DEPT_SEPARATOR = "、";

    private final AllocationOrgMappingRepository repository;

    public AllocationOrgMappingController(AllocationOrgMappingRepository repository) {
        this.repository = repository;
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
                ? repository.searchByKeyword(keyword, pageable)
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

        // 业务规则校验（不通过则报错）
        validateCreate(l1Branch, orgName, orgCode, costCenterCode, deptFullPath);

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

        String l1Branch = body.containsKey("l1_branch") ? body.get("l1_branch").trim() : m.getL1Branch();
        String orgName = body.containsKey("org_name") ? body.get("org_name").trim() : m.getOrgName();
        String orgCode = body.containsKey("org_code") ? body.get("org_code").trim() : m.getOrgCode();
        String costCenterCode = body.containsKey("cost_center_code") ? body.get("cost_center_code").trim() : m.getCostCenterCode();
        String deptFullPath = body.containsKey("dept_full_path")
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

        // 业务规则校验（排除自身）
        validateUpdate(id, l1Branch, orgName, orgCode, costCenterCode, deptFullPath);

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

    // ==================== Import ====================

    @PostMapping("/import")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") Long userId) {
        int count = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        // 缓存当前批次已处理的记录（key：一级分行 + 部门全路径）
        Map<String, AllocationOrgMapping> deptCache = new HashMap<>();
        // 同机构（分行+机构名）代码/成本中心基准（取首条，后续行一致性比对）：key：一级分行+机构名称
        Map<String, String[]> orgRef = new HashMap<>();
        // 规则占用表（均不含软删除行）：
        // (一级分行 + 部门) → 占用记录 id；同分行下部门全路径唯一（规则 1）
        Map<String, Long> deptOwner = new HashMap<>();
        for (AllocationOrgMapping m : repository.findAllByDeletedAtIsNull()) {
            deptOwner.put(deptKey(m.getL1Branch(), m.getDeptFullPath()), m.getId());
            orgRef.putIfAbsent(m.getL1Branch() + "|" + m.getOrgName(),
                    new String[]{m.getOrgCode(), m.getCostCenterCode() == null ? "" : m.getCostCenterCode()});
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
                if (orgName.isBlank() || orgCode.isBlank()) {
                    skipped++;
                    errors.add("第" + (i + 1) + "行: 机构名称、机构代码不能为空");
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

                // 规则 2（一致性）：同一分行同名机构的代码/成本中心须一致（基准：库内首条或本批次首条）
                String[] ref = orgRef.get(l1Branch + "|" + orgName);
                if (ref == null) {
                    orgRef.put(l1Branch + "|" + orgName, new String[]{orgCode, costCenterCode});
                } else {
                    if (!ref[0].equals(orgCode)) {
                        skipped++;
                        errors.add("第" + (i + 1) + "行: 机构名称 " + orgName + " 在 " + l1Branch + " 下已对应机构代码 " + ref[0] + "，与本次 " + orgCode + " 不一致");
                        continue;
                    }
                    String refCost = ref[1];
                    if (!refCost.equals(costCenterCode)) {
                        skipped++;
                        errors.add("第" + (i + 1) + "行: 机构名称 " + orgName + " 在 " + l1Branch + " 下已对应成本中心 " + (refCost.isEmpty() ? "空" : refCost) + "，与本次 " + (costCenterCode.isEmpty() ? "空" : costCenterCode) + " 不一致");
                        continue;
                    }
                }

                m.setL1Branch(l1Branch);
                m.setOrgName(orgName);
                m.setOrgCode(orgCode);
                m.setCostCenterCode(nullableCost(costCenterCode));
                m.setDeptFullPath(deptFullPath);
                m.setRemark(remark);
                repository.save(m);
                repository.flush();
                deptCache.put(deptKey(l1Branch, deptFullPath), m);
                // 登记部门占用（新增记录 id 已生成）
                deptOwner.put(deptKey(l1Branch, deptFullPath), m.getId());
                count++;
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
        result.put("imported", count);
        result.put("skipped", skipped);
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
     * 新增时业务规则校验（不合规抛错）
     * 规则 1：部门全路径同分行唯一；规则 2：同分行同名机构代码/成本中心一致
     * （机构代码同分行唯一 v1.12.150 放开；成本中心跨分行 v1.12.151 放开）
     */
    private void validateCreate(String l1Branch, String orgName, String orgCode,
                                String costCenterCode, String deptFullPath) {
        // 规则 1：部门全路径在同一一级分行内唯一
        checkDeptUniqueness(l1Branch, deptFullPath, null);
        // 规则 2：同分行同名机构的代码/成本中心一致
        checkOrgConsistency(l1Branch, orgName, orgCode, costCenterCode, null);
    }

    /**
     * 编辑时业务规则校验（排除自身，不合规抛错）
     * 规则 1：部门全路径同分行唯一；规则 2：同分行同名机构代码/成本中心一致
     */
    private void validateUpdate(Long id, String l1Branch, String orgName, String orgCode,
                                String costCenterCode, String deptFullPath) {
        checkDeptUniqueness(l1Branch, deptFullPath, id);
        checkOrgConsistency(l1Branch, orgName, orgCode, costCenterCode, id);
    }

    /** 同分行同名机构的代码/成本中心一致性校验（排除自身 id） */
    private void checkOrgConsistency(String l1Branch, String orgName, String orgCode,
                                     String costCenterCode, Long excludeId) {
        for (AllocationOrgMapping m : repository.findAllByDeletedAtIsNull()) {
            if (excludeId != null && m.getId().equals(excludeId)) continue;
            if (!m.getL1Branch().equals(l1Branch) || !m.getOrgName().equals(orgName)) continue;
            if (!m.getOrgCode().equals(orgCode)) {
                throw new RuntimeException("机构名称 " + orgName + " 在 " + l1Branch + " 下已对应机构代码 " + m.getOrgCode() + "，与本次 " + orgCode + " 不一致");
            }
            String existingCost = m.getCostCenterCode() == null ? "" : m.getCostCenterCode();
            String newCost = costCenterCode == null ? "" : costCenterCode;
            if (!existingCost.equals(newCost)) {
                throw new RuntimeException("机构名称 " + orgName + " 在 " + l1Branch + " 下已对应成本中心 " + (existingCost.isEmpty() ? "空" : existingCost) + "，与本次 " + (newCost.isEmpty() ? "空" : newCost) + " 不一致");
            }
            return; // 首条同名记录校验通过即可
        }
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

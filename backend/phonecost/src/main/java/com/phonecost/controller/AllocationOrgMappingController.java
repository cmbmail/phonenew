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
 * 分摊机构对照表
 * 一个机构名称（唯一）对应一个机构代码（唯一）、一个成本中心（唯一），多个部门全路径（、分隔）
 * 部门全路径的值在同一一级分行下只能有唯一值；同一个部门全路径可对应多个不同一级分行
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
        String deptFullPath = normalizeDepts(body.getOrDefault("dept_full_path", ""));

        // 唯一性校验（含软删除记录，因为唯一索引覆盖所有行）
        checkUniqueness(orgName, orgCode, costCenterCode, null);
        // 部门全路径在同一一级分行内唯一
        checkDeptUniqueness(l1Branch, deptFullPath, null);

        AllocationOrgMapping m = new AllocationOrgMapping();
        m.setL1Branch(l1Branch);
        m.setOrgName(orgName);
        m.setOrgCode(orgCode);
        m.setCostCenterCode(costCenterCode);
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
                ? normalizeDepts(body.get("dept_full_path"))
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

        // 唯一性校验：排除自身（含软删除记录，因为唯一索引覆盖所有行）
        checkUniqueness(orgName, orgCode, costCenterCode, id);
        checkDeptUniqueness(l1Branch, deptFullPath, id);

        m.setL1Branch(l1Branch);
        m.setOrgName(orgName);
        m.setOrgCode(orgCode);
        m.setCostCenterCode(costCenterCode);
        m.setDeptFullPath(deptFullPath);
        if (body.containsKey("remark")) m.setRemark(body.get("remark"));
        AllocationOrgMapping saved = repository.save(m);
        return ResponseEntity.ok(ApiResponse.ok(toMap(saved)));
    }

    // ==================== Delete ====================

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        AllocationOrgMapping m = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new RuntimeException("记录不存在: " + id));
        m.setDeletedAt(LocalDateTime.now());
        repository.save(m);
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
        LocalDateTime now = LocalDateTime.now();
        List<AllocationOrgMapping> toDelete = new ArrayList<>();
        for (AllocationOrgMapping m : records) {
            if (m.getDeletedAt() == null) {
                m.setDeletedAt(now);
                toDelete.add(m);
            }
        }
        if (!toDelete.isEmpty()) {
            repository.saveAll(toDelete);
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
        // 缓存当前批次已处理的记录，避免同文件内重复触发唯一索引冲突
        Map<String, AllocationOrgMapping> nameCache = new HashMap<>();
        Map<String, AllocationOrgMapping> codeCache = new HashMap<>();
        Map<String, AllocationOrgMapping> costCache = new HashMap<>();
        // (一级分行 + 部门) → 占用记录 id：预载库内既有占用（不含软删除行，部门无物理唯一索引）
        Map<String, Long> deptOwner = new HashMap<>();
        for (AllocationOrgMapping m : repository.findAllByDeletedAtIsNull()) {
            registerDepts(deptOwner, m.getL1Branch(), m.getDeptFullPath(), m.getId());
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
                String deptFullPath = normalizeDepts(getCellString(row, 4));
                String remark = getCellString(row, 5);
                if (l1Branch.isBlank() && orgName.isBlank() && orgCode.isBlank()
                        && costCenterCode.isBlank() && deptFullPath.isBlank() && remark.isBlank()) continue;
                if (orgName.isBlank() || orgCode.isBlank()) {
                    skipped++;
                    errors.add("第" + (i + 1) + "行: 机构名称、机构代码不能为空");
                    continue;
                }

                // upsert: 按机构名称（主键语义）查找，其次按机构代码（含软删除记录，避免唯一索引冲突）
                AllocationOrgMapping m = nameCache.get(orgName);
                if (m == null) {
                    m = repository.findByOrgName(orgName)
                            .or(() -> repository.findByOrgCode(orgCode))
                            .orElseGet(AllocationOrgMapping::new);
                }
                // 若是软删除记录则恢复
                if (m.getDeletedAt() != null) {
                    m.setDeletedAt(null);
                }
                Long selfId = m.getId();

                // 机构代码/成本中心指向其他记录时拒绝该行（含软删除记录，唯一索引覆盖所有行）
                AllocationOrgMapping byCode = codeCache.get(orgCode);
                if (byCode == null) {
                    byCode = repository.findByOrgCode(orgCode).orElse(null);
                }
                if (byCode != null && !byCode.getId().equals(selfId)) {
                    skipped++;
                    errors.add("第" + (i + 1) + "行: 机构代码 " + orgCode + " 已被 " + byCode.getOrgName() + " 使用");
                    continue;
                }
                if (!costCenterCode.isBlank()) {
                    AllocationOrgMapping byCost = costCache.get(costCenterCode);
                    if (byCost == null) {
                        byCost = repository.findByCostCenterCode(costCenterCode).orElse(null);
                    }
                    if (byCost != null && !byCost.getId().equals(selfId)) {
                        skipped++;
                        errors.add("第" + (i + 1) + "行: 成本中心 " + costCenterCode + " 已被 " + byCost.getOrgName() + " 使用");
                        continue;
                    }
                }

                // 部门全路径在同一一级分行内唯一（占用者为自身时放行，支持原记录更新自己的部门）
                String deptConflict = findDeptConflict(deptOwner, l1Branch, deptFullPath, selfId);
                if (deptConflict != null) {
                    skipped++;
                    errors.add("第" + (i + 1) + "行: 部门全路径 " + deptConflict + " 在 " + l1Branch + " 下已存在");
                    continue;
                }

                // 记录旧部门占用，便于保存后释放
                String oldL1 = m.getL1Branch();
                String oldDepts = m.getDeptFullPath();

                m.setL1Branch(l1Branch);
                m.setOrgName(orgName);
                m.setOrgCode(orgCode);
                m.setCostCenterCode(costCenterCode);
                m.setDeptFullPath(deptFullPath);
                m.setRemark(remark);
                repository.save(m);
                repository.flush();
                nameCache.put(orgName, m);
                codeCache.put(orgCode, m);
                if (!costCenterCode.isBlank()) costCache.put(costCenterCode, m);
                // 释放旧部门占用，登记新部门占用
                unregisterDepts(deptOwner, oldL1, oldDepts, m.getId());
                registerDepts(deptOwner, l1Branch, deptFullPath, m.getId());
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

    /** 规范化部门全路径：拆分、去空、去重、重新以、连接 */
    private String normalizeDepts(String raw) {
        if (raw == null || raw.isBlank()) return "";
        List<String> parts = new ArrayList<>();
        for (String p : raw.split(DEPT_SEPARATOR)) {
            String t = p.trim();
            if (!t.isEmpty() && !parts.contains(t)) parts.add(t);
        }
        return String.join(DEPT_SEPARATOR, parts);
    }

    /** 机构名称/机构代码/成本中心唯一性校验（排除自身 id，含软删除记录，因为唯一索引覆盖所有行） */
    private void checkUniqueness(String orgName, String orgCode, String costCenterCode, Long excludeId) {
        Optional<AllocationOrgMapping> byName = repository.findByOrgName(orgName);
        if (byName.isPresent() && (excludeId == null || !byName.get().getId().equals(excludeId))) {
            throw new RuntimeException("机构名称已存在: " + orgName);
        }
        Optional<AllocationOrgMapping> byCode = repository.findByOrgCode(orgCode);
        if (byCode.isPresent() && (excludeId == null || !byCode.get().getId().equals(excludeId))) {
            throw new RuntimeException("机构代码已存在: " + orgCode);
        }
        if (!costCenterCode.isBlank()) {
            Optional<AllocationOrgMapping> byCost = repository.findByCostCenterCode(costCenterCode);
            if (byCost.isPresent() && (excludeId == null || !byCost.get().getId().equals(excludeId))) {
                throw new RuntimeException("成本中心代码已存在: " + costCenterCode);
            }
        }
    }

    /** 部门全路径在同一一级分行内唯一校验（排除自身 id，不含软删除记录） */
    private void checkDeptUniqueness(String l1Branch, String deptFullPath, Long excludeId) {
        if (deptFullPath == null || deptFullPath.isBlank()) return;
        for (String d : splitDepts(deptFullPath)) {
            for (AllocationOrgMapping m : repository.findAllByDeletedAtIsNull()) {
                if (excludeId != null && m.getId().equals(excludeId)) continue;
                if (!m.getL1Branch().equals(l1Branch)) continue; // 不同一级分行允许相同部门全路径
                for (String e : splitDepts(m.getDeptFullPath())) {
                    if (d.equals(e)) {
                        throw new RuntimeException("部门全路径「" + d + "」在 " + l1Branch + " 下已存在（机构: " + m.getOrgName() + "）");
                    }
                }
            }
        }
    }

    /** 拆分部门全路径为去空列表 */
    private List<String> splitDepts(String deptFullPath) {
        if (deptFullPath == null || deptFullPath.isBlank()) return List.of();
        List<String> parts = new ArrayList<>();
        for (String p : deptFullPath.split(DEPT_SEPARATOR)) {
            String t = p.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        return parts;
    }

    /**
     * 导入用部门冲突检测：占用者为自身记录时放行（支持更新自己的部门）。
     * 返回冲突的部门名（无冲突返回 null）。
     */
    private String findDeptConflict(Map<String, Long> deptOwner, String l1Branch, String deptFullPath, Long selfId) {
        if (deptFullPath == null || deptFullPath.isBlank()) return null;
        for (String d : splitDepts(deptFullPath)) {
            Long owner = deptOwner.get(deptKey(l1Branch, d));
            if (owner != null && !owner.equals(selfId)) {
                return d;
            }
        }
        return null;
    }

    private String deptKey(String l1Branch, String dept) {
        return l1Branch + "|" + dept;
    }

    private void registerDepts(Map<String, Long> deptOwner, String l1Branch, String deptFullPath, Long id) {
        if (id == null || deptFullPath == null || deptFullPath.isBlank()) return;
        for (String d : splitDepts(deptFullPath)) {
            deptOwner.put(deptKey(l1Branch, d), id);
        }
    }

    private void unregisterDepts(Map<String, Long> deptOwner, String l1Branch, String deptFullPath, Long id) {
        if (id == null || deptFullPath == null || deptFullPath.isBlank()) return;
        for (String d : splitDepts(deptFullPath)) {
            deptOwner.remove(deptKey(l1Branch, d), id);
        }
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

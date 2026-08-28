package com.phonecost.controller;

import com.phonecost.domain.OrgCodeMapping;
import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.OrgCodeMappingRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/import/org-code-mapping")
@PreAuthorize("isAuthenticated()")
public class OrgCodeMappingController {

    private final OrgCodeMappingRepository repository;

    public OrgCodeMappingController(OrgCodeMappingRepository repository) {
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

        Page<OrgCodeMapping> pageResult = hasSearch
                ? repository.searchByKeyword(keyword, pageable)
                : repository.findByDeletedAtIsNull(pageable);

        List<Map<String, Object>> items = new ArrayList<>();
        for (OrgCodeMapping m : pageResult.getContent()) {
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
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestBody Map<String, String> body,
            @RequestAttribute("userId") Long userId) {
        String orgCode = body.getOrDefault("org_code", "").trim();
        if (orgCode.isBlank()) {
            throw new RuntimeException("机构代码不能为空");
        }
        if (repository.findByOrgCode(orgCode).isPresent()) {
            throw new RuntimeException("机构代码已存在: " + orgCode);
        }
        OrgCodeMapping m = new OrgCodeMapping();
        m.setL1Branch(body.getOrDefault("l1_branch", ""));
        m.setOrgCode(orgCode);
        m.setOrgName(body.getOrDefault("org_name", ""));
        m.setCostCenterCode(body.getOrDefault("cost_center_code", ""));
        m.setRemark(body.getOrDefault("remark", ""));
        OrgCodeMapping saved = repository.save(m);
        return ResponseEntity.ok(ApiResponse.ok(toMap(saved)));
    }

    // ==================== Update ====================

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestAttribute("userId") Long userId) {
        OrgCodeMapping m = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new RuntimeException("记录不存在: " + id));
        if (body.containsKey("l1_branch")) m.setL1Branch(body.get("l1_branch"));
        if (body.containsKey("org_code")) {
            String newOrgCode = body.get("org_code").trim();
            if (newOrgCode.isBlank()) {
                throw new RuntimeException("机构代码不能为空");
            }
            // 校验唯一性：排除自身（含软删除记录，因为唯一索引覆盖所有行）
            Optional<OrgCodeMapping> existing = repository.findByOrgCode(newOrgCode);
            if (existing.isPresent() && !existing.get().getId().equals(id)) {
                throw new RuntimeException("机构代码已存在: " + newOrgCode);
            }
            m.setOrgCode(newOrgCode);
        }
        if (body.containsKey("org_name")) m.setOrgName(body.get("org_name"));
        if (body.containsKey("cost_center_code")) m.setCostCenterCode(body.get("cost_center_code"));
        if (body.containsKey("remark")) m.setRemark(body.get("remark"));
        OrgCodeMapping saved = repository.save(m);
        return ResponseEntity.ok(ApiResponse.ok(toMap(saved)));
    }

    // ==================== Delete ====================

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        OrgCodeMapping m = repository.findByIdAndDeletedAtIsNull(id)
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
        List<OrgCodeMapping> records = repository.findAllById(idList);
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (OrgCodeMapping m : records) {
            if (m.getDeletedAt() == null) {
                m.setDeletedAt(now);
                repository.save(m);
                count++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("deleted", count);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Import ====================

    @PostMapping("/import")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") Long userId) {
        int count = 0;
        int skipped = 0;
        // 缓存当前批次已处理的 org_code → 实体，避免同文件内重复 org_code 触发唯一索引冲突
        Map<String, OrgCodeMapping> batchCache = new HashMap<>();
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String l1Branch = getCellString(row, 0);
                String orgCode = getCellString(row, 1);
                String orgName = getCellString(row, 2);
                String costCenterCode = getCellString(row, 3);
                String remark = getCellString(row, 4);
                if (l1Branch.isBlank() && orgCode.isBlank() && orgName.isBlank() && costCenterCode.isBlank() && remark.isBlank()) continue;
                if (orgCode.isBlank()) continue;

                // upsert: 先查批次缓存，再查数据库（含软删除记录，避免唯一索引冲突）
                OrgCodeMapping m = batchCache.get(orgCode);
                if (m == null) {
                    m = repository.findByOrgCode(orgCode)
                            .orElseGet(OrgCodeMapping::new);
                }
                // 如果是软删除记录，恢复它（清空 deletedAt）
                if (m.getDeletedAt() != null) {
                    m.setDeletedAt(null);
                }
                m.setL1Branch(l1Branch);
                m.setOrgCode(orgCode);
                m.setOrgName(orgName);
                m.setCostCenterCode(costCenterCode);
                m.setRemark(remark);
                repository.save(m);
                // flush 确保数据库状态与缓存一致
                repository.flush();
                batchCache.put(orgCode, m);
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
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Export ====================

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportEntries() {
        List<OrgCodeMapping> items = repository.findAllForExport();

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("组织机构对照表");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"一级分行", "机构代码", "机构名称", "成本中心代码", "备注"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (OrgCodeMapping m : items) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(m.getL1Branch() != null ? m.getL1Branch() : "");
                row.createCell(1).setCellValue(m.getOrgCode() != null ? m.getOrgCode() : "");
                row.createCell(2).setCellValue(m.getOrgName() != null ? m.getOrgName() : "");
                row.createCell(3).setCellValue(m.getCostCenterCode() != null ? m.getCostCenterCode() : "");
                row.createCell(4).setCellValue(m.getRemark() != null ? m.getRemark() : "");
            }

            wb.write(out);
            String fileName = URLEncoder.encode("组织机构对照表导出.xlsx", StandardCharsets.UTF_8);
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
            Sheet sheet = wb.createSheet("组织机构对照表");
            Row headerRow = sheet.createRow(0);
            String[] headers = {"一级分行", "机构代码", "机构名称", "成本中心代码", "备注"};
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
                sheet.setColumnWidth(i, 6000);
            }
            wb.write(out);
            String fileName = URLEncoder.encode("组织机构对照表导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成模板失败", e);
        }
    }

    // ==================== Helpers ====================

    private Map<String, Object> toMap(OrgCodeMapping m) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", m.getId());
        map.put("l1_branch", m.getL1Branch() != null ? m.getL1Branch() : "");
        map.put("org_code", m.getOrgCode() != null ? m.getOrgCode() : "");
        map.put("org_name", m.getOrgName() != null ? m.getOrgName() : "");
        map.put("cost_center_code", m.getCostCenterCode() != null ? m.getCostCenterCode() : "");
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
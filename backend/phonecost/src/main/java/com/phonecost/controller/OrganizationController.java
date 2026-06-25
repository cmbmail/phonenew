package com.phonecost.controller;

import com.phonecost.domain.SysOrganization;
import com.phonecost.dto.ApiResponse;
import com.phonecost.service.DataScope;
import com.phonecost.service.DataScopeService;
import com.phonecost.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/org")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;
    private final DataScopeService dataScopeService;

    @GetMapping("/tree")
    public ResponseEntity<ApiResponse<List<SysOrganization>>> getTree(
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        List<SysOrganization> allOrgs = organizationService.getTree();
        List<SysOrganization> filtered = scope.filterByOrgId(
                allOrgs.stream().filter(o -> o.getDeletedAt() == null).toList(),
                SysOrganization::getId);
        return ResponseEntity.ok(ApiResponse.ok(filtered));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SysOrganization>> getById(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isOrgVisible(id)) {
            throw new IllegalArgumentException("无权访问该组织数据");
        }
        return ResponseEntity.ok(ApiResponse.ok(organizationService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SysOrganization>> create(@RequestBody SysOrganization org) {
        return ResponseEntity.ok(ApiResponse.ok(organizationService.create(org)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SysOrganization>> update(@PathVariable Long id,
                                                                 @RequestBody SysOrganization org) {
        return ResponseEntity.ok(ApiResponse.ok(organizationService.update(id, org)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        organizationService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importFromExcel(
            @RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> result = organizationService.importFromExcel(file);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            throw new IllegalArgumentException("组织导入失败: " + e.getMessage());
        }
    }

    @PostMapping("/rebuild-paths")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> rebuildPaths() {
        organizationService.rebuildPaths();
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @GetMapping("/import-template")
    public ResponseEntity<ByteArrayResource> downloadImportTemplate() throws Exception {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("组织机构导入模板");

            // Header style
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            // Headers
            String[] headers = {"组织名称", "组织代码", "成本中心"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }
            sheet.setColumnWidth(0, 12000);
            sheet.setColumnWidth(1, 5000);
            sheet.setColumnWidth(2, 5000);

            // Sample rows
            String[][] samples = {
                {"集团总部", "001", "CC001"},
                {"集团总部/北京分行", "002", "CC002"},
                {"集团总部/北京分行/海淀支行", "003", "CC003"},
                {"集团总部/北京分行/海淀支行/科技部", "004", "CC004"},
            };
            for (int i = 0; i < samples.length; i++) {
                Row row = sheet.createRow(i + 1);
                for (int j = 0; j < samples[i].length; j++) {
                    row.createCell(j).setCellValue(samples[i][j]);
                }
            }

            // Notes row
            Row noteRow = sheet.createRow(samples.length + 2);
            CellStyle noteStyle = wb.createCellStyle();
            Font noteFont = wb.createFont();
            noteFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            noteFont.setItalic(true);
            noteStyle.setFont(noteFont);
            Cell noteCell = noteRow.createCell(0);
            noteCell.setCellValue("说明：组织名称为必填项，各级以 / 分隔（如 集团总部/北京分行/海淀支行）；组织代码和成本中心选填，为空时显示为 \"-\"");
            noteCell.setCellStyle(noteStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(samples.length + 2, samples.length + 2, 0, 2));

            wb.write(out);

            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentDispositionFormData("attachment", "组织机构导入模板.xlsx");
            httpHeaders.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

            return ResponseEntity.ok()
                    .headers(httpHeaders)
                    .contentLength(out.size())
                    .body(new ByteArrayResource(out.toByteArray()));
        }
    }
}

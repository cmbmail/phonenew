package com.phonecost.service;

import com.phonecost.domain.AllocationResult;
import com.phonecost.domain.BillDetail;
import com.phonecost.domain.SysOrganization;
import com.phonecost.repository.AllocationResultRepository;
import com.phonecost.repository.BillDetailRepository;
import com.phonecost.repository.SysOrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分行导出服务
 * V2格式（匹配银行财务系统实际模板）：
 * - 分行费用分摊汇总.xlsx: 成本中心 + 费用小计(单位：元)
 * - 分行费用分摊明细.xlsx: 号码 + 成本中心 + 费用小计(单位：元)
 * 成本中心 = sys_organization.code
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllocationExportService {

    private final AllocationResultRepository resultRepository;
    private final BillDetailRepository billDetailRepository;
    private final SysOrganizationRepository orgRepository;
    private final AuditLogService auditLogService;

    /**
     * Export summary Excel for a branch org
     * 分行费用分摊汇总.xlsx: 成本中心 + 费用小计
     */
    public byte[] exportSummary(Long batchId, Long branchOrgId) throws IOException {
        List<AllocationResult> results = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);

        // Filter to descendants of branchOrgId (or all if null)
        Map<Long, SysOrganization> orgMap = buildOrgMap();
        List<AllocationResult> filtered = filterByBranch(results, branchOrgId, orgMap);

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("费用分摊汇总");

            // Header
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("成本中心");
            header.createCell(1).setCellValue("费用小计(单位：元)");

            // Style header bold
            CellStyle headerStyle = wb.createCellStyle();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            headerStyle.setFont(boldFont);
            header.getCell(0).setCellStyle(headerStyle);
            header.getCell(1).setCellStyle(headerStyle);

            // Data rows
            int rowIdx = 1;
            for (AllocationResult r : filtered) {
                SysOrganization org = orgMap.get(r.getOrgId());
                String code = org != null ? org.getCode() : "";

                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(code);
                Cell feeCell = row.createCell(1);
                feeCell.setCellValue(r.getTotalFee() != null ? r.getTotalFee().doubleValue() : 0);
            }

            // Auto-size columns
            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);

            wb.write(out);
            auditLogService.log(0L, "system", "EXPORT_SUMMARY", "bill_batch", batchId,
                    "{\"branch_org_id\":" + branchOrgId + "}");
            return out.toByteArray();
        }
    }

    /**
     * Export detail Excel for a branch org
     * 分行费用分摊明细.xlsx: 号码 + 成本中心 + 费用小计
     */
    public byte[] exportDetail(Long batchId, Long branchOrgId) throws IOException {
        List<BillDetail> details = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);

        Map<Long, SysOrganization> orgMap = buildOrgMap();

        // Filter details by branch
        List<BillDetail> filtered;
        if (branchOrgId != null) {
            SysOrganization branch = orgMap.get(branchOrgId);
            String branchPath = branch != null ? branch.getPath() : "";
            filtered = details.stream()
                    .filter(d -> {
                        if (d.getOrgId() == null) return false;
                        SysOrganization dOrg = orgMap.get(d.getOrgId());
                        return dOrg != null && dOrg.getPath() != null && dOrg.getPath().startsWith(branchPath);
                    })
                    .collect(Collectors.toList());
        } else {
            filtered = details;
        }

        // Merge details by phone number (sum all sheet types)
        Map<String, BigDecimal> phoneFees = filtered.stream()
                .filter(d -> "CALL".equals(d.getSheetType())) // Only count CALL sheet total
                .collect(Collectors.groupingBy(
                        BillDetail::getPhoneNumber,
                        Collectors.reducing(BigDecimal.ZERO,
                                d -> d.getTotalFee() != null ? d.getTotalFee() : BigDecimal.ZERO,
                                BigDecimal::add)
                ));

        // Add recording/crbt/flash_msg fees
        for (BillDetail d : filtered) {
            if (!"CALL".equals(d.getSheetType())) {
                BigDecimal fee = d.getTotalFee() != null ? d.getTotalFee() : BigDecimal.ZERO;
                phoneFees.merge(d.getPhoneNumber(), fee, BigDecimal::add);
            }
        }

        // Get org mapping per phone number
        Map<String, Long> phoneOrgMap = filtered.stream()
                .filter(d -> d.getOrgId() != null)
                .collect(Collectors.toMap(
                        BillDetail::getPhoneNumber,
                        BillDetail::getOrgId,
                        (a, b) -> a // first wins
                ));

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("费用分摊明细");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("号码");
            header.createCell(1).setCellValue("成本中心");
            header.createCell(2).setCellValue("费用小计(单位：元)");

            CellStyle headerStyle = wb.createCellStyle();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            headerStyle.setFont(boldFont);
            header.getCell(0).setCellStyle(headerStyle);
            header.getCell(1).setCellStyle(headerStyle);
            header.getCell(2).setCellStyle(headerStyle);

            int rowIdx = 1;
            for (Map.Entry<String, BigDecimal> entry : phoneFees.entrySet()) {
                String phone = entry.getKey();
                BigDecimal fee = entry.getValue();

                Long orgId = phoneOrgMap.get(phone);
                SysOrganization org = orgId != null ? orgMap.get(orgId) : null;
                String code = org != null ? org.getCode() : "";

                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(phone);
                row.createCell(1).setCellValue(code);
                Cell feeCell = row.createCell(2);
                feeCell.setCellValue(fee.doubleValue());
            }

            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);
            sheet.autoSizeColumn(2);

            wb.write(out);
            auditLogService.log(0L, "system", "EXPORT_DETAIL", "bill_batch", batchId,
                    "{\"branch_org_id\":" + branchOrgId + "}");
            return out.toByteArray();
        }
    }

    private Map<Long, SysOrganization> buildOrgMap() {
        return orgRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() == null)
                .collect(Collectors.toMap(SysOrganization::getId, o -> o));
    }

    private List<AllocationResult> filterByBranch(List<AllocationResult> results,
                                                   Long branchOrgId,
                                                   Map<Long, SysOrganization> orgMap) {
        if (branchOrgId == null) return results;

        SysOrganization branch = orgMap.get(branchOrgId);
        if (branch == null) return results;

        String branchPath = branch.getPath();
        return results.stream()
                .filter(r -> {
                    SysOrganization rOrg = orgMap.get(r.getOrgId());
                    return rOrg != null && rOrg.getPath() != null && rOrg.getPath().startsWith(branchPath);
                })
                .collect(Collectors.toList());
    }
}

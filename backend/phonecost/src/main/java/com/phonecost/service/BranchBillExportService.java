package com.phonecost.service;

import com.phonecost.domain.*;
import com.phonecost.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分行账单导出服务
 * 生成包含5个Sheet的完整分行账单Excel文件：
 *
 * Sheet1 - 费用分摊汇总：组织名称 + 6项费用明细 + 合计 + 确认状态
 * Sheet2 - 号码费用明细：号码 + 成本中心 + 月租/通话/录音/彩铃/闪信/合计
 * Sheet3 - 原始账单行：所有 bill_detail 逐行（含归属来源、例外标记）
 * Sheet4 - 调整记录：该批次的费用调整历史（如有）
 * Sheet5 - 封面信息：批次号 / 账单月份 / 导出时间 / 操作人 / 统计摘要
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BranchBillExportService {

    private final AllocationResultRepository resultRepository;
    private final BillDetailRepository billDetailRepository;
    private final AllocationAdjustmentRepository adjustmentRepository;
    private final SysOrganizationRepository orgRepository;
    private final BillBatchRepository billBatchRepository;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Export complete branch bill as a single Excel with 5 sheets
     */
    public byte[] exportBranchBill(Long batchId, Long branchOrgId, Long operatorId) throws IOException {
        // Load data
        BillBatch batch = billBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("账单批次不存在: " + batchId));
        List<AllocationResult> allResults = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<AllocationAdjustment> adjustments = adjustmentRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();

        // Filter by branch
        String branchPath = branchOrgId != null && orgMap.containsKey(branchOrgId)
                ? orgMap.get(branchOrgId).getPath() : null;

        List<AllocationResult> results = filterByPath(allResults, branchPath, orgMap, true);
        List<BillDetail> details = filterDetailsByPath(allDetails, branchPath, orgMap);

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Create styles
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle numberStyle = createNumberStyle(wb);
            CellStyle titleStyle = createTitleStyle(wb);
            CellStyle labelStyle = createLabelStyle(wb);

            // ===== Sheet 5: 封面信息 (first sheet for visual impact) =====
            writeCoverSheet(wb, batch, branchOrgId, orgMap, results, details, adjustments,
                    operatorId, titleStyle, labelStyle);

            // ===== Sheet 1: 费用分摊汇总 =====
            writeSummarySheet(wb, results, orgMap, headerStyle, numberStyle);

            // ===== Sheet 2: 号码费用明细 =====
            writePhoneDetailSheet(wb, details, orgMap, headerStyle, numberStyle);

            // ===== Sheet 3: 原始账单行 =====
            writeRawDetailSheet(wb, details, orgMap, headerStyle, numberStyle);

            // ===== Sheet 4: 调整记录 =====
            writeAdjustmentSheet(wb, adjustments, headerStyle);

            wb.write(out);

            auditLogService.log(operatorId != null ? operatorId : 0L, "user",
                    "EXPORT_BRANCH_BILL", "bill_batch", batchId,
                    "{\"branch_org_id\":" + (branchOrgId != null ? branchOrgId : "null") + "}");

            log.info("Branch bill exported: batch={}, branch={}, results={}, details={}, adjustments={}",
                    batchId, branchOrgId, results.size(), details.size(), adjustments.size());

            return out.toByteArray();
        }
    }

    // ==================== Sheet 5: 封面信息 ====================

    private void writeCoverSheet(XSSFWorkbook wb, BillBatch batch, Long branchOrgId,
                                  Map<Long, SysOrganization> orgMap,
                                  List<AllocationResult> results, List<BillDetail> details,
                                  List<AllocationAdjustment> adjustments,
                                  Long operatorId, CellStyle titleStyle, CellStyle labelStyle) {
        Sheet sheet = wb.createSheet("封面信息");

        String branchName = branchOrgId != null && orgMap.containsKey(branchOrgId)
                ? orgMap.get(branchOrgId).getName() : "全部分行";
        BigDecimal totalFee = results.stream()
                .map(r -> r.getTotalFee() != null ? r.getTotalFee() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int confirmedCount = (int) results.stream().filter(r -> r.getConfirmStatus() == 1).count();
        int phoneCount = (int) details.stream()
                .filter(d -> "CALL".equals(d.getSheetType()))
                .map(BillDetail::getPhoneNumber).distinct().count();

        int row = 0;
        row = createTitleRow(sheet, row, "银行电话费用分摊账单", titleStyle, wb);
        row++;

        row = createLabelValue(sheet, row, "分行名称", branchName, labelStyle);
        row = createLabelValue(sheet, row, "账单月份", batch.getBillingMonth(), labelStyle);
        row = createLabelValue(sheet, row, "批次号", batch.getBatchNo(), labelStyle);
        row = createLabelValue(sheet, row, "导出时间", LocalDateTime.now().format(DTF), labelStyle);
        row++;
        row = createLabelValue(sheet, row, "组织数量", String.valueOf(results.size()), labelStyle);
        row = createLabelValue(sheet, row, "号码数量", String.valueOf(phoneCount), labelStyle);
        row = createLabelValue(sheet, row, "费用合计", "¥" + totalFee.setScale(2).toPlainString(), labelStyle);
        row = createLabelValue(sheet, row, "已确认组织", String.valueOf(confirmedCount) + "/" + results.size(), labelStyle);
        row = createLabelValue(sheet, row, "调整次数", String.valueOf(adjustments.size()), labelStyle);

        sheet.setColumnWidth(0, 5000);
        sheet.setColumnWidth(1, 8000);
    }

    // ==================== Sheet 1: 费用分摊汇总 ====================

    private void writeSummarySheet(XSSFWorkbook wb, List<AllocationResult> results,
                                    Map<Long, SysOrganization> orgMap,
                                    CellStyle headerStyle, CellStyle numberStyle) {
        Sheet sheet = wb.createSheet("费用分摊汇总");

        Row header = sheet.createRow(0);
        String[] headers = {"组织名称", "成本中心", "月租费", "通话费", "录音费", "彩铃费", "闪信费", "合计", "号码数", "确认状态"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowIdx = 1;
        for (AllocationResult r : results) {
            Row row = sheet.createRow(rowIdx++);
            SysOrganization org = orgMap.get(r.getOrgId());
            String code = org != null ? (org.getCode() != null ? org.getCode() : "") : "";

            row.createCell(0).setCellValue(r.getOrgName());
            row.createCell(1).setCellValue(code);
            setCurrencyCell(row.createCell(2), r.getMonthlyRent(), numberStyle);
            setCurrencyCell(row.createCell(3), r.getCallFee(), numberStyle);
            setCurrencyCell(row.createCell(4), r.getRecordingFee(), numberStyle);
            setCurrencyCell(row.createCell(5), r.getCrbtFee(), numberStyle);
            setCurrencyCell(row.createCell(6), r.getFlashMsgFee(), numberStyle);
            setCurrencyCell(row.createCell(7), r.getTotalFee(), numberStyle);
            row.createCell(8).setCellValue(r.getPhoneCount() != null ? r.getPhoneCount() : 0);
            row.createCell(9).setCellValue(confirmStatusLabel(r.getConfirmStatus()));

            // Highlight unconfirmed rows
            if (r.getConfirmStatus() == 0) {
                CellStyle pendingStyle = wb.createCellStyle();
                Font font = wb.createFont();
                font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
                pendingStyle.setFont(font);
                row.getCell(9).setCellStyle(pendingStyle);
            }
        }

        // Total row
        if (!results.isEmpty()) {
            Row totalRow = sheet.createRow(rowIdx++);
            totalRow.createCell(0).setCellValue("合计");
            CellStyle boldStyle = wb.createCellStyle();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            boldStyle.setFont(boldFont);
            totalRow.getCell(0).setCellStyle(boldStyle);

            BigDecimal sumMonthlyRent = BigDecimal.ZERO, sumCall = BigDecimal.ZERO;
            BigDecimal sumRecording = BigDecimal.ZERO, sumCrbt = BigDecimal.ZERO;
            BigDecimal sumFlash = BigDecimal.ZERO, sumTotal = BigDecimal.ZERO;
            int sumPhones = 0;

            for (AllocationResult r : results) {
                sumMonthlyRent = safeAdd(sumMonthlyRent, r.getMonthlyRent());
                sumCall = safeAdd(sumCall, r.getCallFee());
                sumRecording = safeAdd(sumRecording, r.getRecordingFee());
                sumCrbt = safeAdd(sumCrbt, r.getCrbtFee());
                sumFlash = safeAdd(sumFlash, r.getFlashMsgFee());
                sumTotal = safeAdd(sumTotal, r.getTotalFee());
                sumPhones += r.getPhoneCount() != null ? r.getPhoneCount() : 0;
            }

            setCurrencyCell(totalRow.createCell(2), sumMonthlyRent, numberStyle);
            setCurrencyCell(totalRow.createCell(3), sumCall, numberStyle);
            setCurrencyCell(totalRow.createCell(4), sumRecording, numberStyle);
            setCurrencyCell(totalRow.createCell(5), sumCrbt, numberStyle);
            setCurrencyCell(totalRow.createCell(6), sumFlash, numberStyle);
            setCurrencyCell(totalRow.createCell(7), sumTotal, numberStyle);
            totalRow.createCell(8).setCellValue(sumPhones);
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ==================== Sheet 2: 号码费用明细 ====================

    private void writePhoneDetailSheet(XSSFWorkbook wb, List<BillDetail> details,
                                        Map<Long, SysOrganization> orgMap,
                                        CellStyle headerStyle, CellStyle numberStyle) {
        Sheet sheet = wb.createSheet("号码费用明细");

        Row header = sheet.createRow(0);
        String[] headers = {"外线号码", "分机号", "成本中心", "归属来源", "月租费", "通话费", "录音费", "彩铃费", "闪信费", "合计"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Aggregate by phone number across all sheets
        Map<String, PhoneFeeSummary> phoneSummaries = details.stream()
                .collect(Collectors.groupingBy(
                        BillDetail::getPhoneNumber,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                this::summarizePhoneFees
                        )
                ));

        int rowIdx = 1;
        for (Map.Entry<String, PhoneFeeSummary> entry : phoneSummaries.entrySet()) {
            PhoneFeeSummary summary = entry.getValue();
            Row row = sheet.createRow(rowIdx++);

            row.createCell(0).setCellValue(entry.getKey());
            row.createCell(1).setCellValue(summary.extension);
            SysOrganization org = orgMap.get(summary.orgId);
            row.createCell(2).setCellValue(org != null && org.getCode() != null ? org.getCode() : "");
            row.createCell(3).setCellValue(summary.ownershipSource);
            setCurrencyCell(row.createCell(4), summary.monthlyRent, numberStyle);
            setCurrencyCell(row.createCell(5), summary.callFee, numberStyle);
            setCurrencyCell(row.createCell(6), summary.recordingFee, numberStyle);
            setCurrencyCell(row.createCell(7), summary.crbtFee, numberStyle);
            setCurrencyCell(row.createCell(8), summary.flashMsgFee, numberStyle);
            setCurrencyCell(row.createCell(9), summary.totalFee, numberStyle);
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ==================== Sheet 3: 原始账单行 ====================

    private void writeRawDetailSheet(XSSFWorkbook wb, List<BillDetail> details,
                                      Map<Long, SysOrganization> orgMap,
                                      CellStyle headerStyle, CellStyle numberStyle) {
        Sheet sheet = wb.createSheet("原始账单行");

        Row header = sheet.createRow(0);
        String[] headers = {"外线号码", "分机号", "费用类型", "月租费", "通话费", "录音费", "彩铃费", "闪信费", "合计",
                "归属组织", "成本中心", "归属来源", "例外标记", "借调标记"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowIdx = 1;
        for (BillDetail d : details) {
            Row row = sheet.createRow(rowIdx++);

            row.createCell(0).setCellValue(d.getPhoneNumber());
            row.createCell(1).setCellValue(d.getExtension() != null ? d.getExtension() : "");
            row.createCell(2).setCellValue(sheetTypeLabel(d.getSheetType()));
            setCurrencyCell(row.createCell(3), d.getMonthlyRent(), numberStyle);
            setCurrencyCell(row.createCell(4), d.getCallFee(), numberStyle);
            setCurrencyCell(row.createCell(5), d.getRecordingFee(), numberStyle);
            setCurrencyCell(row.createCell(6), d.getCrbtFee(), numberStyle);
            setCurrencyCell(row.createCell(7), d.getFlashMsgFee(), numberStyle);
            setCurrencyCell(row.createCell(8), d.getTotalFee(), numberStyle);

            SysOrganization org = d.getOrgId() != null ? orgMap.get(d.getOrgId()) : null;
            row.createCell(9).setCellValue(org != null ? org.getName() : (d.getOrgId() == null ? "未归属" : ""));
            row.createCell(10).setCellValue(org != null && org.getCode() != null ? org.getCode() : "");
            row.createCell(11).setCellValue(d.getOwnershipSource() != null ? d.getOwnershipSource() : "");
            row.createCell(12).setCellValue(d.getIsException() != null && d.getIsException() == 1 ? "是" : "否");
            row.createCell(13).setCellValue(d.getIsSeconded() != null && d.getIsSeconded() == 1 ? "是" : "否");
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ==================== Sheet 4: 调整记录 ====================

    private void writeAdjustmentSheet(XSSFWorkbook wb, List<AllocationAdjustment> adjustments,
                                        CellStyle headerStyle) {
        Sheet sheet = wb.createSheet("调整记录");

        Row header = sheet.createRow(0);
        String[] headers = {"序号", "号码", "原组织", "目标组织", "调整金额", "原因", "操作时间"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowIdx = 1;
        for (int i = 0; i < adjustments.size(); i++) {
            AllocationAdjustment adj = adjustments.get(i);
            Row row = sheet.createRow(rowIdx++);

            row.createCell(0).setCellValue(i + 1);
            row.createCell(1).setCellValue(adj.getPhoneNumber());
            row.createCell(2).setCellValue(adj.getFromOrgName());
            row.createCell(3).setCellValue(adj.getToOrgName());
            Cell amountCell = row.createCell(4);
            amountCell.setCellValue(adj.getAmount() != null ? adj.getAmount().doubleValue() : 0);
            row.createCell(5).setCellValue(adj.getReason());
            row.createCell(6).setCellValue(
                    adj.getCreatedAt() != null ? adj.getCreatedAt().format(DTF) : "");
        }

        if (adjustments.isEmpty()) {
            Row emptyRow = sheet.createRow(1);
            emptyRow.createCell(0).setCellValue("暂无调整记录");
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ==================== Helper classes & methods ====================

    /** Aggregated fee summary per phone number */
    private static class PhoneFeeSummary {
        String extension = "";
        Long orgId;
        String ownershipSource = "";
        BigDecimal monthlyRent = BigDecimal.ZERO;
        BigDecimal callFee = BigDecimal.ZERO;
        BigDecimal recordingFee = BigDecimal.ZERO;
        BigDecimal crbtFee = BigDecimal.ZERO;
        BigDecimal flashMsgFee = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
    }

    private PhoneFeeSummary summarizePhoneFees(List<BillDetail> details) {
        PhoneFeeSummary s = new PhoneFeeSummary();
        for (BillDetail d : details) {
            s.extension = d.getExtension() != null ? d.getExtension() : s.extension;
            if (s.orgId == null) s.orgId = d.getOrgId();
            if (d.getOwnershipSource() != null && !d.getOwnershipSource().isEmpty())
                s.ownershipSource = d.getOwnershipSource();
            s.monthlyRent = safeAdd(s.monthlyRent, d.getMonthlyRent());
            s.callFee = safeAdd(s.callFee, d.getCallFee());
            s.recordingFee = safeAdd(s.recordingFee, d.getRecordingFee());
            s.crbtFee = safeAdd(s.crbtFee, d.getCrbtFee());
            s.flashMsgFee = safeAdd(s.flashMsgFee, d.getFlashMsgFee());
            s.totalFee = safeAdd(s.totalFee, d.getTotalFee());
        }
        return s;
    }

    // ==================== Style factories ====================

    private static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createNumberStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0.00"));
        return style;
    }

    private static CellStyle createTitleStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 18);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private static CellStyle createLabelStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    // ==================== Utility methods ====================

    private static void setCurrencyCell(Cell cell, BigDecimal value, CellStyle numberStyle) {
        double v = value != null ? value.doubleValue() : 0;
        cell.setCellValue(v);
        cell.setCellStyle(numberStyle);
    }

    private static String confirmStatusLabel(Byte status) {
        if (status == null) return "未知";
        return switch (status.intValue()) {
            case 0 -> "待确认";
            case 1 -> "已确认";
            case 2 -> "已撤回";
            default -> "未知";
        };
    }

    private static String sheetTypeLabel(String type) {
        if (type == null) return "其他";
        return switch (type) {
            case "CALL" -> "通话费";
            case "RECORDING" -> "录音费";
            case "CRBT" -> "彩铃费";
            case "FLASH_MSG" -> "闪信费";
            default -> type;
        };
    }

    private static int createTitleRow(Sheet sheet, int startRow, String text, CellStyle style, Workbook wb) {
        Row row = sheet.createRow(startRow);
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        cell.setCellStyle(style);
        // Merge columns A-B for title
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(startRow, startRow, 0, 1));
        return startRow;
    }

    private static int createLabelValue(Sheet sheet, int rowIdx, String label, String value,
                                          CellStyle labelStyle) {
        Row row = sheet.createRow(rowIdx);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label + ":");
        labelCell.setCellStyle(labelStyle);
        row.createCell(1).setCellValue(value);
        return rowIdx + 1;
    }

    private static void autoSizeColumns(Sheet sheet, int colCount) {
        for (int i = 0; i < colCount; i++) {
            sheet.autoSizeColumn(i);
            // Set minimum width to prevent too-narrow columns
            int width = sheet.getColumnWidth(i);
            if (width < 2000) sheet.setColumnWidth(i, 2000);
        }
    }

    private Map<Long, SysOrganization> buildOrgMap() {
        return orgRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() == null)
                .collect(Collectors.toMap(SysOrganization::getId, o -> o));
    }

    private List<AllocationResult> filterByPath(List<AllocationResult> items, String pathPrefix,
                                                  Map<Long, SysOrganization> orgMap, boolean includeUnassigned) {
        if (pathPrefix == null || pathPrefix.isEmpty()) return items;
        return items.stream().filter(r -> {
            if (r.getOrgId() != null && r.getOrgId() == -1L) return includeUnassigned;
            SysOrganization org = orgMap.get(r.getOrgId());
            return org != null && org.getPath() != null && org.getPath().startsWith(pathPrefix);
        }).collect(Collectors.toList());
    }

    private List<BillDetail> filterDetailsByPath(List<BillDetail> items, String pathPrefix,
                                                   Map<Long, SysOrganization> orgMap) {
        if (pathPrefix == null || pathPrefix.isEmpty()) return items;
        return items.stream().filter(d -> {
            if (d.getOrgId() == null) return false;
            SysOrganization org = orgMap.get(d.getOrgId());
            return org != null && org.getPath() != null && org.getPath().startsWith(pathPrefix);
        }).collect(Collectors.toList());
    }

    private static BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        return (a != null ? a : BigDecimal.ZERO).add(b != null ? b : BigDecimal.ZERO);
    }
}

package com.phonecost.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分行账单导出服务（对齐一级分行账单.xlsx样表格式）
 *
 * 分行账单（5个Sheet）：
 * Sheet1 - 分摊汇总表：分行 + 平台使用费/码号月租费/时长/费用/合计 (13列)
 * Sheet2 - 按号码费用：一级分行/部门代码/部门名称/外线号码 + 费用明细 (13列)
 * Sheet3 - 录音费用：一级分行/部门代码/部门名称/分机号/号码/录音目录/费用 (7列)
 * Sheet4 - 闪信费用：一级分行/部门代码/部门名称/号码/月份/下发量/金额 (7列)
 * Sheet5 - 彩铃费用：一级分行/部门代码/部门名称/分机号/号码/费用 (6列)
 *
 * 另外提供分行成本中心对照表导出 (7列)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BranchBillExportService {

    private final AllocationResultRepository resultRepository;
    private final BillDetailRepository billDetailRepository;
    private final SysOrganizationRepository orgRepository;
    private final BillBatchRepository billBatchRepository;
    private final AuditLogService auditLogService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    // ==================== Main Export: Branch Bill (5 Sheets) ====================

    public byte[] exportBranchBill(Long batchId, Long branchOrgId, Long operatorId) throws IOException {
        BillBatch batch = billBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("账单批次不存在: " + batchId));
        List<AllocationResult> allResults = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();

        // Filter by branch
        String branchPath = branchOrgId != null && orgMap.containsKey(branchOrgId)
                ? orgMap.get(branchOrgId).getPath() : null;

        List<AllocationResult> results = filterByPath(allResults, branchPath, orgMap, true);
        List<BillDetail> details = filterDetailsByPath(allDetails, branchPath, orgMap);

        String monthLabel = formatMonthLabel(batch.getBillingMonth());

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle numberStyle = createNumberStyle(wb);

            writeSummarySheet(wb, monthLabel, results, details, orgMap, headerStyle, numberStyle);
            writePhoneDetailSheet(wb, monthLabel, details, orgMap, headerStyle, numberStyle);
            writeRecordingSheet(wb, monthLabel, details, orgMap, headerStyle, numberStyle);
            writeFlashSheet(wb, monthLabel, details, orgMap, headerStyle, numberStyle);
            writeCrbtSheet(wb, monthLabel, details, orgMap, headerStyle, numberStyle);

            wb.write(out);

            auditLogService.log(operatorId != null ? operatorId : 0L, "user",
                    "EXPORT_BRANCH_BILL", "bill_batch", batchId,
                    "{\"branch_org_id\":" + (branchOrgId != null ? branchOrgId : "null") + "}");

            log.info("Branch bill exported: batch={}, branch={}, results={}, details={}",
                    batchId, branchOrgId, results.size(), details.size());

            return out.toByteArray();
        }
    }

    // ==================== Sheet1: 分摊汇总表 (13 cols) ====================

    private void writeSummarySheet(XSSFWorkbook wb, String monthLabel,
                                   List<AllocationResult> results, List<BillDetail> details,
                                   Map<Long, SysOrganization> orgMap,
                                   CellStyle headerStyle, CellStyle numberStyle) {
        Sheet sheet = wb.createSheet(monthLabel + "分摊汇总表");
        String[] headers = {"分行", "平台使用费", "码号月租费", "国内外呼时长", "转接外呼时长",
                "国内费用", "国际时长", "国际费用", "费用小计",
                "录音费用", "彩铃费用", "闪信费用", "合计"};

        writeHeaderRow(sheet, headers, headerStyle);

        // Group CALL details by ancestor branch
        Map<Long, List<BillDetail>> callByBranch = details.stream()
                .filter(d -> "CALL".equals(d.getSheetType()))
                .collect(Collectors.groupingBy(d -> resolveBranchId(d.getOrgId(), orgMap),
                        LinkedHashMap::new, Collectors.toList()));

        // Group allocation results by ancestor branch
        Map<Long, List<AllocationResult>> resultsByBranch = results.stream()
                .collect(Collectors.groupingBy(r -> resolveBranchId(r.getOrgId(), orgMap),
                        LinkedHashMap::new, Collectors.toList()));

        // Merge all branch IDs maintaining order
        Set<Long> branchIds = new LinkedHashSet<>();
        branchIds.addAll(callByBranch.keySet());
        branchIds.addAll(resultsByBranch.keySet());

        int rowIdx = 1;
        for (Long branchId : branchIds) {
            Row row = sheet.createRow(rowIdx++);
            String branchName = branchId != null && orgMap.containsKey(branchId)
                    ? orgMap.get(branchId).getName() : "未归属";

            // Column 0: 分行
            row.createCell(0).setCellValue(branchName);

            // Aggregate raw_data from CALL details
            BigDecimal sumPlatform = ZERO, sumMonthlyRentCode = ZERO;
            BigDecimal sumDomesticDuration = ZERO, sumTransferDuration = ZERO;
            BigDecimal sumDomesticFee = ZERO, sumIntDuration = ZERO, sumIntFee = ZERO;

            for (BillDetail d : callByBranch.getOrDefault(branchId, List.of())) {
                sumPlatform = safeAdd(sumPlatform, getRawDecimalOrZero(d.getRawData(), "platformFee"));
                sumMonthlyRentCode = safeAdd(sumMonthlyRentCode, getRawDecimalOrZero(d.getRawData(), "monthlyRentCode"));
                sumDomesticDuration = safeAdd(sumDomesticDuration, getRawDecimalOrZero(d.getRawData(), "domesticDuration"));
                sumTransferDuration = safeAdd(sumTransferDuration, getRawDecimalOrZero(d.getRawData(), "transferDuration"));
                sumDomesticFee = safeAdd(sumDomesticFee, getRawDecimalOrZero(d.getRawData(), "domesticFee"));
                sumIntDuration = safeAdd(sumIntDuration, getRawDecimalOrZero(d.getRawData(), "internationalDuration"));
                sumIntFee = safeAdd(sumIntFee, getRawDecimalOrZero(d.getRawData(), "internationalFee"));
            }

            BigDecimal feeSubtotal = sumPlatform.add(sumMonthlyRentCode).add(sumDomesticFee).add(sumIntFee);

            // Columns 1-8: fee breakdown
            setCurrencyCell(row.createCell(1), sumPlatform, numberStyle);
            setCurrencyCell(row.createCell(2), sumMonthlyRentCode, numberStyle);
            row.createCell(3).setCellValue(sumDomesticDuration.doubleValue());
            row.createCell(4).setCellValue(sumTransferDuration.doubleValue());
            setCurrencyCell(row.createCell(5), sumDomesticFee, numberStyle);
            row.createCell(6).setCellValue(sumIntDuration.doubleValue());
            setCurrencyCell(row.createCell(7), sumIntFee, numberStyle);
            setCurrencyCell(row.createCell(8), feeSubtotal, numberStyle);

            // Recording/CRBT/Flash from allocation results
            BigDecimal sumRec = ZERO, sumCrbt = ZERO, sumFlash = ZERO;
            for (AllocationResult r : resultsByBranch.getOrDefault(branchId, List.of())) {
                sumRec = safeAdd(sumRec, r.getRecordingFee());
                sumCrbt = safeAdd(sumCrbt, r.getCrbtFee());
                sumFlash = safeAdd(sumFlash, r.getFlashMsgFee());
            }

            // Columns 9-12
            setCurrencyCell(row.createCell(9), sumRec, numberStyle);
            setCurrencyCell(row.createCell(10), sumCrbt, numberStyle);
            setCurrencyCell(row.createCell(11), sumFlash, numberStyle);

            BigDecimal grandTotal = feeSubtotal.add(sumRec).add(sumCrbt).add(sumFlash);
            setCurrencyCell(row.createCell(12), grandTotal, numberStyle);
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ==================== Sheet2: 按号码费用 (13 cols) ====================

    private void writePhoneDetailSheet(XSSFWorkbook wb, String monthLabel,
                                       List<BillDetail> details,
                                       Map<Long, SysOrganization> orgMap,
                                       CellStyle headerStyle, CellStyle numberStyle) {
        Sheet sheet = wb.createSheet(monthLabel + "按号码费用");
        String[] headers = {"一级分行", "部门代码", "部门名称", "外线号码",
                "平台使用费", "码号月租费", "国内外呼时长", "转接外呼时长",
                "国内费用", "国际时长", "国际费用", "费用小计", "备注"};

        writeHeaderRow(sheet, headers, headerStyle);

        List<BillDetail> callDetails = details.stream()
                .filter(d -> "CALL".equals(d.getSheetType()))
                .collect(Collectors.toList());

        int rowIdx = 1;
        for (BillDetail d : callDetails) {
            Row row = sheet.createRow(rowIdx++);

            String branchName = findBranchName(d.getOrgId(), orgMap);
            SysOrganization org = d.getOrgId() != null ? orgMap.get(d.getOrgId()) : null;
            String deptCode = org != null && org.getCode() != null ? org.getCode() : "";
            String deptName = org != null ? org.getName() : "";

            row.createCell(0).setCellValue(branchName);
            row.createCell(1).setCellValue(deptCode);
            row.createCell(2).setCellValue(deptName);
            row.createCell(3).setCellValue(d.getPhoneNumber());

            BigDecimal platformFee = getRawDecimalOrZero(d.getRawData(), "platformFee");
            BigDecimal monthlyRentCode = getRawDecimalOrZero(d.getRawData(), "monthlyRentCode");
            BigDecimal domesticDuration = getRawDecimalOrZero(d.getRawData(), "domesticDuration");
            BigDecimal transferDuration = getRawDecimalOrZero(d.getRawData(), "transferDuration");
            BigDecimal domesticFee = getRawDecimalOrZero(d.getRawData(), "domesticFee");
            BigDecimal intDuration = getRawDecimalOrZero(d.getRawData(), "internationalDuration");
            BigDecimal intFee = getRawDecimalOrZero(d.getRawData(), "internationalFee");

            setCurrencyCell(row.createCell(4), platformFee, numberStyle);
            setCurrencyCell(row.createCell(5), monthlyRentCode, numberStyle);
            row.createCell(6).setCellValue(domesticDuration.doubleValue());
            row.createCell(7).setCellValue(transferDuration.doubleValue());
            setCurrencyCell(row.createCell(8), domesticFee, numberStyle);
            row.createCell(9).setCellValue(intDuration.doubleValue());
            setCurrencyCell(row.createCell(10), intFee, numberStyle);

            BigDecimal subtotal = platformFee.add(monthlyRentCode).add(domesticFee).add(intFee);
            setCurrencyCell(row.createCell(11), subtotal, numberStyle);

            // 备注: exception or seconded info
            String remark = "";
            if (d.getIsException() != null && d.getIsException() == 1) remark = "例外";
            if (d.getIsSeconded() != null && d.getIsSeconded() == 1)
                remark = remark.isEmpty() ? "借调" : remark + "/借调";
            row.createCell(12).setCellValue(remark);
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ==================== Sheet3: 录音费用 (7 cols) ====================

    private void writeRecordingSheet(XSSFWorkbook wb, String monthLabel,
                                     List<BillDetail> details,
                                     Map<Long, SysOrganization> orgMap,
                                     CellStyle headerStyle, CellStyle numberStyle) {
        Sheet sheet = wb.createSheet(monthLabel + "录音费用");
        String[] headers = {"一级分行", "部门代码", "部门名称", "分机号", "号码", "录音目录", "费用小计(单位：元)"};

        writeHeaderRow(sheet, headers, headerStyle);

        List<BillDetail> recDetails = details.stream()
                .filter(d -> "RECORDING".equals(d.getSheetType()))
                .collect(Collectors.toList());

        int rowIdx = 1;
        for (BillDetail d : recDetails) {
            Row row = sheet.createRow(rowIdx++);

            row.createCell(0).setCellValue(findBranchName(d.getOrgId(), orgMap));
            SysOrganization org = d.getOrgId() != null ? orgMap.get(d.getOrgId()) : null;
            row.createCell(1).setCellValue(org != null && org.getCode() != null ? org.getCode() : "");
            row.createCell(2).setCellValue(org != null ? org.getName() : "");
            row.createCell(3).setCellValue(d.getExtension() != null ? d.getExtension() : "");
            row.createCell(4).setCellValue(d.getPhoneNumber());
            row.createCell(5).setCellValue(getRawString(d.getRawData(), "recordingDir"));
            setCurrencyCell(row.createCell(6), d.getRecordingFee(), numberStyle);
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ==================== Sheet4: 闪信费用 (7 cols) ====================

    private void writeFlashSheet(XSSFWorkbook wb, String monthLabel,
                                 List<BillDetail> details,
                                 Map<Long, SysOrganization> orgMap,
                                 CellStyle headerStyle, CellStyle numberStyle) {
        Sheet sheet = wb.createSheet(monthLabel + "闪信费用");
        String[] headers = {"一级分行", "部门代码", "部门名称", "号码", "月份", "下发量", "金额"};

        writeHeaderRow(sheet, headers, headerStyle);

        List<BillDetail> flashDetails = details.stream()
                .filter(d -> "FLASH_MSG".equals(d.getSheetType()))
                .collect(Collectors.toList());

        int rowIdx = 1;
        for (BillDetail d : flashDetails) {
            Row row = sheet.createRow(rowIdx++);

            row.createCell(0).setCellValue(findBranchName(d.getOrgId(), orgMap));
            SysOrganization org = d.getOrgId() != null ? orgMap.get(d.getOrgId()) : null;
            row.createCell(1).setCellValue(org != null && org.getCode() != null ? org.getCode() : "");
            row.createCell(2).setCellValue(org != null ? org.getName() : "");
            row.createCell(3).setCellValue(d.getPhoneNumber());
            row.createCell(4).setCellValue(d.getFlashMonth() != null ? d.getFlashMonth() : "");

            BigDecimal flashCount = getRawDecimalOrZero(d.getRawData(), "flashCount");
            row.createCell(5).setCellValue(flashCount.doubleValue());
            setCurrencyCell(row.createCell(6), d.getFlashMsgFee(), numberStyle);
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ==================== Sheet5: 彩铃费用 (6 cols) ====================

    private void writeCrbtSheet(XSSFWorkbook wb, String monthLabel,
                                List<BillDetail> details,
                                Map<Long, SysOrganization> orgMap,
                                CellStyle headerStyle, CellStyle numberStyle) {
        Sheet sheet = wb.createSheet(monthLabel + "彩铃费用");
        String[] headers = {"一级分行", "部门代码", "部门名称", "分机号", "号码", "费用"};

        writeHeaderRow(sheet, headers, headerStyle);

        List<BillDetail> crbtDetails = details.stream()
                .filter(d -> "CRBT".equals(d.getSheetType()))
                .collect(Collectors.toList());

        int rowIdx = 1;
        for (BillDetail d : crbtDetails) {
            Row row = sheet.createRow(rowIdx++);

            row.createCell(0).setCellValue(findBranchName(d.getOrgId(), orgMap));
            SysOrganization org = d.getOrgId() != null ? orgMap.get(d.getOrgId()) : null;
            row.createCell(1).setCellValue(org != null && org.getCode() != null ? org.getCode() : "");
            row.createCell(2).setCellValue(org != null ? org.getName() : "");
            row.createCell(3).setCellValue(d.getExtension() != null ? d.getExtension() : "");
            row.createCell(4).setCellValue(d.getPhoneNumber());
            setCurrencyCell(row.createCell(5), d.getCrbtFee(), numberStyle);
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ==================== Cost Center Mapping Export (7 cols) ====================

    public byte[] exportCostCenterMapping(Long batchId, Long branchOrgId, Long operatorId) throws IOException {
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();

        // Filter by branch
        String branchPath = branchOrgId != null && orgMap.containsKey(branchOrgId)
                ? orgMap.get(branchOrgId).getPath() : null;
        List<BillDetail> details = filterDetailsByPath(allDetails, branchPath, orgMap);

        // Distinct phone numbers (first occurrence wins)
        Map<String, BillDetail> phoneMap = new LinkedHashMap<>();
        for (BillDetail d : details) {
            phoneMap.putIfAbsent(d.getPhoneNumber(), d);
        }

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("分行成本中心对照表");
            String[] headers = {"电话号码", "分行", "部门代码", "部门名称", "成本中心", "例外", "备注"};

            CellStyle headerStyle = createHeaderStyle(wb);
            writeHeaderRow(sheet, headers, headerStyle);

            int rowIdx = 1;
            for (Map.Entry<String, BillDetail> entry : phoneMap.entrySet()) {
                BillDetail d = entry.getValue();
                Row row = sheet.createRow(rowIdx++);

                row.createCell(0).setCellValue(d.getPhoneNumber());
                row.createCell(1).setCellValue(findBranchName(d.getOrgId(), orgMap));

                SysOrganization org = d.getOrgId() != null ? orgMap.get(d.getOrgId()) : null;
                row.createCell(2).setCellValue(org != null && org.getCode() != null ? org.getCode() : "");
                row.createCell(3).setCellValue(org != null ? buildFullNamePath(d.getOrgId(), orgMap) : "");
                row.createCell(4).setCellValue(org != null && org.getCode() != null ? org.getCode() : "");

                row.createCell(5).setCellValue(d.getIsException() != null && d.getIsException() == 1 ? "是" : "否");

                String remark = "";
                if (d.getIsSeconded() != null && d.getIsSeconded() == 1) remark = "借调";
                row.createCell(6).setCellValue(remark);
            }

            autoSizeColumns(sheet, headers.length);
            wb.write(out);

            auditLogService.log(operatorId != null ? operatorId : 0L, "user",
                    "EXPORT_COST_CENTER_MAPPING", "bill_batch", batchId,
                    "{\"branch_org_id\":" + (branchOrgId != null ? branchOrgId : "null") + "}");

            return out.toByteArray();
        }
    }

    // ==================== Raw Data JSON Helpers ====================

    private BigDecimal getRawDecimal(String rawData, String field) {
        if (rawData == null || rawData.isEmpty() || rawData.equals("{}")) return null;
        try {
            Map<String, Object> map = MAPPER.readValue(rawData, new TypeReference<Map<String, Object>>() {});
            Object val = map.get(field);
            if (val == null) return null;
            if (val instanceof Number) return BigDecimal.valueOf(((Number) val).doubleValue());
            if (val instanceof String) {
                String s = ((String) val).trim();
                return s.isEmpty() ? null : new BigDecimal(s);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal getRawDecimalOrZero(String rawData, String field) {
        BigDecimal val = getRawDecimal(rawData, field);
        return val != null ? val : BigDecimal.ZERO;
    }

    private String getRawString(String rawData, String field) {
        if (rawData == null || rawData.isEmpty() || rawData.equals("{}")) return "";
        try {
            Map<String, Object> map = MAPPER.readValue(rawData, new TypeReference<Map<String, Object>>() {});
            Object val = map.get(field);
            return val != null ? val.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== Org Hierarchy Helpers ====================

    /** Find the ancestor (or self) that is a 一级分行 (type=2) */
    private SysOrganization findBranchOrg(Long orgId, Map<Long, SysOrganization> orgMap) {
        if (orgId == null) return null;
        Set<Long> visited = new HashSet<>();
        SysOrganization org = orgMap.get(orgId);
        while (org != null && !visited.contains(org.getId())) {
            if (org.getType() != null && org.getType() == 2) return org;
            if (org.getType() != null && org.getType() == 1) return null; // reached root, no branch
            visited.add(org.getId());
            if (org.getParentId() == null || org.getParentId() == 0L) break;
            org = orgMap.get(org.getParentId());
        }
        return null;
    }

    private String findBranchName(Long orgId, Map<Long, SysOrganization> orgMap) {
        SysOrganization branch = findBranchOrg(orgId, orgMap);
        if (branch != null) return branch.getName();
        // Fallback: use org's own name if no branch ancestor found
        SysOrganization org = orgId != null ? orgMap.get(orgId) : null;
        return org != null ? org.getName() : "";
    }

    private Long resolveBranchId(Long orgId, Map<Long, SysOrganization> orgMap) {
        SysOrganization branch = findBranchOrg(orgId, orgMap);
        return branch != null ? branch.getId() : (orgId != null ? orgId : -1L);
    }

    /** Build full name path like "贵阳分行/遵义分行/行政管理部" */
    private String buildFullNamePath(Long orgId, Map<Long, SysOrganization> orgMap) {
        if (orgId == null) return "";
        List<String> names = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        SysOrganization org = orgMap.get(orgId);
        while (org != null && !visited.contains(org.getId())) {
            if (org.getType() != null && org.getType() == 1) break; // skip root (集团)
            names.add(0, org.getName());
            visited.add(org.getId());
            if (org.getParentId() == null || org.getParentId() == 0L) break;
            org = orgMap.get(org.getParentId());
        }
        return String.join("/", names);
    }

    /** Format "2026-03" → "2026年3月" */
    private String formatMonthLabel(String billingMonth) {
        if (billingMonth == null || billingMonth.isEmpty()) return "";
        try {
            String[] parts = billingMonth.split("-");
            if (parts.length >= 2) {
                int year = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                return year + "年" + month + "月";
            }
        } catch (NumberFormatException ignored) {}
        return billingMonth;
    }

    // ==================== Style Factories ====================

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

    // ==================== Utility Methods ====================

    private void writeHeaderRow(Sheet sheet, String[] headers, CellStyle headerStyle) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private static void setCurrencyCell(Cell cell, BigDecimal value, CellStyle numberStyle) {
        double v = value != null ? value.doubleValue() : 0;
        cell.setCellValue(v);
        cell.setCellStyle(numberStyle);
    }

    private static void autoSizeColumns(Sheet sheet, int colCount) {
        for (int i = 0; i < colCount; i++) {
            sheet.autoSizeColumn(i);
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

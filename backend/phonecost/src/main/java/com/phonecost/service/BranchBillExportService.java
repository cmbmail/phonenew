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
 * 三级分摊导出服务
 *
 * 三级分摊模型：
 *   L1 分摊汇总：集团 → 一级分行（北京分行、上海分行）
 *   L2 一级分行：一级分行 → 直属下级（二级分行+直属部门+支行）
 *   L3 二级分行：二级分行 → 下属部门+支行
 *
 * 每个模块导出独立的Excel文件，包含该层级视角的费用明细
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

    // ==================== L1: 分摊汇总（集团→一级分行） ====================

    /**
     * 导出L1分摊汇总表：每个一级分行一行，汇总其所有下属组织的费用
     */
    public byte[] exportLevel1Summary(Long batchId, Long operatorId) throws IOException {
        BillBatch batch = billBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("账单批次不存在: " + batchId));
        List<AllocationResult> allResults = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();

        // 找出所有一级分行(type=2)
        List<SysOrganization> branches = orgMap.values().stream()
                .filter(o -> o.getType() != null && o.getType() == 2 && o.getDeletedAt() == null)
                .sorted(Comparator.comparing(SysOrganization::getId))
                .collect(Collectors.toList());

        String monthLabel = formatMonthLabel(batch.getBillingMonth());

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle numberStyle = createNumberStyle(wb);
            CellStyle boldStyle = createBoldStyle(wb);

            // Sheet1: 分摊汇总表
            Sheet sheet = wb.createSheet(monthLabel + "集团分摊汇总");
            String[] headers = {"一级分行", "成本中心", "平台使用费", "码号月租费",
                    "国内外呼时长(分钟)", "转接外呼时长(分钟)", "国内费用",
                    "国际时长(分钟)", "国际费用", "费用小计",
                    "录音费用", "彩铃费用", "闪信费用", "合计", "号码数"};
            writeHeaderRow(sheet, headers, headerStyle);

            int rowIdx = 1;
            BigDecimal grandTotal = ZERO;
            int grandPhones = 0;

            for (SysOrganization branch : branches) {
                String branchPath = branch.getPath();
                // 聚合该一级分行下所有子组织的allocation_result
                List<AllocationResult> childResults = allResults.stream()
                        .filter(r -> {
                            if (r.getOrgId() == null || r.getOrgId() == -1L) return false;
                            SysOrganization rOrg = orgMap.get(r.getOrgId());
                            return rOrg != null && rOrg.getPath() != null
                                    && rOrg.getPath().startsWith(branchPath);
                        })
                        .collect(Collectors.toList());

                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(branch.getName());
                row.createCell(1).setCellValue(branch.getCode() != null ? branch.getCode() : "");

                // 从bill_detail按原始列聚合费用（需要raw_data）
                AggregatedFees fees = aggregateFeesByOrgPath(batchId, branchPath, orgMap);

                setCurrencyCell(row.createCell(2), fees.platformFee, numberStyle);
                setCurrencyCell(row.createCell(3), fees.monthlyRentCode, numberStyle);
                row.createCell(4).setCellValue(fees.domesticDuration.doubleValue());
                row.createCell(5).setCellValue(fees.transferDuration.doubleValue());
                setCurrencyCell(row.createCell(6), fees.domesticFee, numberStyle);
                row.createCell(7).setCellValue(fees.internationalDuration.doubleValue());
                setCurrencyCell(row.createCell(8), fees.internationalFee, numberStyle);

                BigDecimal callSubtotal = fees.platformFee.add(fees.monthlyRentCode)
                        .add(fees.domesticFee).add(fees.internationalFee);
                setCurrencyCell(row.createCell(9), callSubtotal, numberStyle);

                // 录音/彩铃/闪信从allocation_result聚合
                BigDecimal sumRec = safeSum(childResults, AllocationResult::getRecordingFee);
                BigDecimal sumCrbt = safeSum(childResults, AllocationResult::getCrbtFee);
                BigDecimal sumFlash = safeSum(childResults, AllocationResult::getFlashMsgFee);
                int phoneCount = childResults.stream()
                        .mapToInt(r -> r.getPhoneCount() != null ? r.getPhoneCount() : 0).sum();

                setCurrencyCell(row.createCell(10), sumRec, numberStyle);
                setCurrencyCell(row.createCell(11), sumCrbt, numberStyle);
                setCurrencyCell(row.createCell(12), sumFlash, numberStyle);

                BigDecimal total = callSubtotal.add(sumRec).add(sumCrbt).add(sumFlash);
                setCurrencyCell(row.createCell(13), total, numberStyle);
                row.createCell(14).setCellValue(phoneCount);

                grandTotal = grandTotal.add(total);
                grandPhones += phoneCount;
            }

            // 合计行
            if (!branches.isEmpty()) {
                Row totalRow = sheet.createRow(rowIdx++);
                totalRow.createCell(0).setCellValue("合计");
                totalRow.getCell(0).setCellStyle(boldStyle);
                // Re-calculate totals from data for accuracy
                List<AllocationResult> allValid = allResults.stream()
                        .filter(r -> r.getOrgId() != null && r.getOrgId() != -1L)
                        .collect(Collectors.toList());
                setCurrencyCell(totalRow.createCell(13),
                        safeSum(allValid, AllocationResult::getTotalFee), numberStyle);
                totalRow.createCell(14).setCellValue(grandPhones);
                totalRow.getCell(14).setCellStyle(boldStyle);
            }

            autoSizeColumns(sheet, headers.length);
            wb.write(out);

            auditLog(operatorId, "EXPORT_L1_SUMMARY", batchId,
                    "{\"module\":\"L1_summary\"}");
            log.info("L1 summary exported: batch={}, branches={}", batchId, branches.size());
            return out.toByteArray();
        }
    }

    // ==================== L2: 一级分行明细（一级分行→直属下级） ====================

    /**
     * 导出L2一级分行明细：某一级分行下所有直属子组织（二级分行+部门+支行）的费用
     */
    public byte[] exportLevel2BranchDetail(Long batchId, Long branchOrgId, Long operatorId) throws IOException {
        BillBatch batch = billBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("账单批次不存在: " + batchId));
        SysOrganization branch = orgMapGet(branchOrgId);
        if (branch == null) throw new IllegalArgumentException("组织不存在: " + branchOrgId);

        List<AllocationResult> allResults = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();

        String branchPath = branch.getPath();
        String monthLabel = formatMonthLabel(batch.getBillingMonth());
        String branchName = branch.getName();

        // 该一级分行的直接子节点
        List<SysOrganization> directChildren = orgMap.values().stream()
                .filter(o -> o.getDeletedAt() == null
                        && Objects.equals(o.getParentId(), branchOrgId))
                .sorted(Comparator.<SysOrganization>comparingInt(o -> o.getType() != null ? o.getType() : 99)
                        .thenComparing(SysOrganization::getName))
                .collect(Collectors.toList());

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle numberStyle = createNumberStyle(wb);
            CellStyle boldStyle = createBoldStyle(wb);

            // Sheet1: 分行分摊汇总（直属下级）
            writeL2SummarySheet(wb, monthLabel, branchName, directChildren, allResults,
                    batchId, orgMap, headerStyle, numberStyle, boldStyle);

            // Sheet2: 按号码费用（该分行下所有CALL明细）
            writePhoneDetailSheet(wb, monthLabel + branchName, allDetails, orgMap,
                    branchPath, headerStyle, numberStyle);

            // Sheet3-5: 录音/闪信/彩铃
            writeRecordingSheet(wb, monthLabel + branchName, allDetails, orgMap,
                    branchPath, headerStyle, numberStyle);
            writeFlashSheet(wb, monthLabel + branchName, allDetails, orgMap,
                    branchPath, headerStyle, numberStyle);
            writeCrbtSheet(wb, monthLabel + branchName, allDetails, orgMap,
                    branchPath, headerStyle, numberStyle);

            wb.write(out);
            auditLog(operatorId, "EXPORT_L2_BRANCH_DETAIL", batchId,
                    "{\"module\":\"L2_branch\",\"branch_org_id\":" + branchOrgId + "}");
            log.info("L2 branch detail exported: batch={}, branch={}, children={}",
                    batchId, branchName, directChildren.size());
            return out.toByteArray();
        }
    }

    // ==================== L3: 二级分行明细（二级分行→下属） ====================

    /**
     * 导出L3二级分行明细：某二级分行下所有直属子组织（部门+支行）的费用
     */
    public byte[] exportLevel3SubBranchDetail(Long batchId, Long subBranchOrgId, Long operatorId) throws IOException {
        BillBatch batch = billBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("账单批次不存在: " + batchId));
        SysOrganization subBranch = orgMapGet(subBranchOrgId);
        if (subBranch == null) throw new IllegalArgumentException("组织不存在: " + subBranchOrgId);

        List<AllocationResult> allResults = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();

        String subBranchPath = subBranch.getPath();
        String monthLabel = formatMonthLabel(batch.getBillingMonth());
        String subBranchName = subBranch.getName();
        String parentBranchName = findParentBranchName(subBranchOrgId, orgMap);

        // 该二级分行的直接子节点
        List<SysOrganization> directChildren = orgMap.values().stream()
                .filter(o -> o.getDeletedAt() == null
                        && Objects.equals(o.getParentId(), subBranchOrgId))
                .sorted(Comparator.<SysOrganization>comparingInt(o -> o.getType() != null ? o.getType() : 99)
                        .thenComparing(SysOrganization::getName))
                .collect(Collectors.toList());

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle numberStyle = createNumberStyle(wb);
            CellStyle boldStyle = createBoldStyle(wb);

            // Sheet1: 二级分行分摊汇总
            writeL3SummarySheet(wb, monthLabel, parentBranchName, subBranchName,
                    directChildren, allResults, orgMap, headerStyle, numberStyle, boldStyle);

            // Sheet2: 按号码费用
            writePhoneDetailSheet(wb, monthLabel + subBranchName, allDetails, orgMap,
                    subBranchPath, headerStyle, numberStyle);

            // Sheet3-5: 录音/闪信/彩铃
            writeRecordingSheet(wb, monthLabel + subBranchName, allDetails, orgMap,
                    subBranchPath, headerStyle, numberStyle);
            writeFlashSheet(wb, monthLabel + subBranchName, allDetails, orgMap,
                    subBranchPath, headerStyle, numberStyle);
            writeCrbtSheet(wb, monthLabel + subBranchName, allDetails, orgMap,
                    subBranchPath, headerStyle, numberStyle);

            wb.write(out);
            auditLog(operatorId, "EXPORT_L3_SUB_BRANCH_DETAIL", batchId,
                    "{\"module\":\"L3_sub_branch\",\"sub_branch_org_id\":" + subBranchOrgId + "}");
            log.info("L3 sub-branch detail exported: batch={}, subBranch={}, children={}",
                    batchId, subBranchName, directChildren.size());
            return out.toByteArray();
        }
    }

    // ==================== L2 Summary Sheet ====================

    private void writeL2SummarySheet(XSSFWorkbook wb, String monthLabel, String branchName,
                                      List<SysOrganization> children,
                                      List<AllocationResult> allResults, Long batchId,
                                      Map<Long, SysOrganization> orgMap,
                                      CellStyle headerStyle, CellStyle numberStyle,
                                      CellStyle boldStyle) {
        Sheet sheet = wb.createSheet(monthLabel + branchName + "_分摊汇总");

        String[] headers = {"序号", "组织类型", "组织名称", "成本中心",
                "平台使用费", "码号月租费", "国内费用", "国际费用",
                "通话费小计", "录音费用", "彩铃费用", "闪信费用", "合计", "号码数", "确认状态"};
        writeHeaderRow(sheet, headers, headerStyle);

        int rowIdx = 1;
        int seq = 0;
        BigDecimal colTotal = ZERO;
        int colPhones = 0;

        for (SysOrganization child : children) {
            Row row = sheet.createRow(rowIdx++);
            seq++;
            List<AllocationResult> childRes = allResults.stream()
                    .filter(r -> Objects.equals(r.getOrgId(), child.getId()))
                    .collect(Collectors.toList());

            row.createCell(0).setCellValue(seq);
            row.createCell(1).setCellValue(orgTypeLabel(child.getType()));
            row.createCell(2).setCellValue(child.getName());
            row.createCell(3).setCellValue(child.getCode() != null ? child.getCode() : "");

            // 从bill_detail聚合原始费用
            AggregatedFees fees = aggregateFeesByOrgId(batchId, child.getId(), orgMap);
            setCurrencyCell(row.createCell(4), fees.platformFee, numberStyle);
            setCurrencyCell(row.createCell(5), fees.monthlyRentCode, numberStyle);
            setCurrencyCell(row.createCell(6), fees.domesticFee, numberStyle);
            setCurrencyCell(row.createCell(7), fees.internationalFee, numberStyle);

            BigDecimal callSub = fees.platformFee.add(fees.monthlyRentCode)
                    .add(fees.domesticFee).add(fees.internationalFee);
            setCurrencyCell(row.createCell(8), callSub, numberStyle);

            BigDecimal sumRec = safeSum(childRes, AllocationResult::getRecordingFee);
            BigDecimal sumCrbt = safeSum(childRes, AllocationResult::getCrbtFee);
            BigDecimal sumFlash = safeSum(childRes, AllocationResult::getFlashMsgFee);
            int phones = childRes.stream()
                    .mapToInt(r -> r.getPhoneCount() != null ? r.getPhoneCount() : 0).sum();

            setCurrencyCell(row.createCell(9), sumRec, numberStyle);
            setCurrencyCell(row.createCell(10), sumCrbt, numberStyle);
            setCurrencyCell(row.createCell(11), sumFlash, numberStyle);

            BigDecimal total = callSub.add(sumRec).add(sumCrbt).add(sumFlash);
            setCurrencyCell(row.createCell(12), total, numberStyle);
            row.createCell(13).setCellValue(phones);

            Byte confirmStatus = !childRes.isEmpty() ? childRes.get(0).getConfirmStatus() : 0;
            row.createCell(14).setCellValue(confirmStatusLabel(confirmStatus));

            colTotal = colTotal.add(total);
            colPhones += phones;
        }

        // 合计行
        if (!children.isEmpty()) {
            Row totalRow = sheet.createRow(rowIdx++);
            totalRow.createCell(0).setCellValue("");
            totalRow.createCell(1).setCellValue("");
            totalRow.createCell(2).setCellValue("合计");
            totalRow.getCell(2).setCellStyle(boldStyle);
            setCurrencyCell(totalRow.createCell(12), colTotal, numberStyle);
            totalRow.getCell(12).setCellStyle(boldStyle);
            totalRow.createCell(13).setCellValue(colPhones);
            totalRow.getCell(13).setCellStyle(boldStyle);
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ==================== L3 Summary Sheet ====================

    private void writeL3SummarySheet(XSSFWorkbook wb, String monthLabel,
                                     String parentBranchName, String subBranchName,
                                     List<SysOrganization> children,
                                     List<AllocationResult> allResults,
                                     Map<Long, SysOrganization> orgMap,
                                     CellStyle headerStyle, CellStyle numberStyle,
                                     CellStyle boldStyle) {
        Sheet sheet = wb.createSheet(monthLabel + subBranchName + "_分摊汇总");

        String[] headers = {"序号", "组织类型", "组织名称", "成本中心",
                "平台使用费", "码号月租费", "国内费用", "国际费用",
                "通话费小计", "录音费用", "彩铃费用", "闪信费用", "合计", "号码数"};
        writeHeaderRow(sheet, headers, headerStyle);

        int rowIdx = 1;
        int seq = 0;
        BigDecimal colTotal = ZERO;
        int colPhones = 0;

        for (SysOrganization child : children) {
            Row row = sheet.createRow(rowIdx++);
            seq++;
            List<AllocationResult> childRes = allResults.stream()
                    .filter(r -> Objects.equals(r.getOrgId(), child.getId()))
                    .collect(Collectors.toList());

            row.createCell(0).setCellValue(seq);
            row.createCell(1).setCellValue(orgTypeLabel(child.getType()));
            row.createCell(2).setCellValue(child.getName());
            row.createCell(3).setCellValue(child.getCode() != null ? child.getCode() : "");

            AggregatedFees fees = aggregateFeesByOrgId(batchId, child.getId(), orgMap);
            setCurrencyCell(row.createCell(4), fees.platformFee, numberStyle);
            setCurrencyCell(row.createCell(5), fees.monthlyRentCode, numberStyle);
            setCurrencyCell(row.createCell(6), fees.domesticFee, numberStyle);
            setCurrencyCell(row.createCell(7), fees.internationalFee, numberStyle);

            BigDecimal callSub = fees.platformFee.add(fees.monthlyRentCode)
                    .add(fees.domesticFee).add(fees.internationalFee);
            setCurrencyCell(row.createCell(8), callSub, numberStyle);

            BigDecimal sumRec = safeSum(childRes, AllocationResult::getRecordingFee);
            BigDecimal sumCrbt = safeSum(childRes, AllocationResult::getCrbtFee);
            BigDecimal sumFlash = safeSum(childRes, AllocationResult::getFlashMsgFee);
            int phones = childRes.stream()
                    .mapToInt(r -> r.getPhoneCount() != null ? r.getPhoneCount() : 0).sum();

            setCurrencyCell(row.createCell(9), sumRec, numberStyle);
            setCurrencyCell(row.createCell(10), sumCrbt, numberStyle);
            setCurrencyCell(row.createCell(11), sumFlash, numberStyle);

            BigDecimal total = callSub.add(sumRec).add(sumCrbt).add(sumFlash);
            setCurrencyCell(row.createCell(12), total, numberStyle);
            row.createCell(13).setCellValue(phones);

            colTotal = colTotal.add(total);
            colPhones += phones;
        }

        if (!children.isEmpty()) {
            Row totalRow = sheet.createRow(rowIdx++);
            totalRow.createCell(2).setCellValue("合计");
            totalRow.getCell(2).setCellStyle(boldStyle);
            setCurrencyCell(totalRow.createCell(12), colTotal, numberStyle);
            totalRow.getCell(12).setCellStyle(boldStyle);
            totalRow.createCell(13).setCellValue(colPhones);
            totalRow.getCell(13).setCellStyle(boldStyle);
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ==================== Detail Sheets (shared by L2/L3) ====================

    private void writePhoneDetailSheet(XSSFWorkbook wb, String sheetPrefix,
                                       List<BillDetail> details,
                                       Map<Long, SysOrganization> orgMap,
                                       String pathPrefix,
                                       CellStyle headerStyle, CellStyle numberStyle) {
        Sheet sheet = wb.createSheet(sheetPrefix + "_号码费用");
        String[] headers = {"一级分行", "部门代码", "部门名称", "外线号码",
                "平台使用费", "码号月租费", "国内外呼时长", "转接外呼时长",
                "国内费用", "国际时长", "国际费用", "费用小计", "备注"};
        writeHeaderRow(sheet, headers, headerStyle);

        List<BillDetail> callDetails = details.stream()
                .filter(d -> "CALL".equals(d.getSheetType()))
                .filter(d -> isInPath(d.getOrgId(), pathPrefix, orgMap))
                .collect(Collectors.toList());

        int rowIdx = 1;
        for (BillDetail d : callDetails) {
            Row row = sheet.createRow(rowIdx++);
            SysOrganization org = d.getOrgId() != null ? orgMap.get(d.getOrgId()) : null;

            row.createCell(0).setCellValue(findBranchName(d.getOrgId(), orgMap));
            row.createCell(1).setCellValue(org != null && org.getCode() != null ? org.getCode() : "");
            row.createCell(2).setCellValue(org != null ? org.getName() : "");
            row.createCell(3).setCellValue(d.getPhoneNumber());

            setCurrencyCell(row.createCell(4), getRawDecimalOrZero(d.getRawData(), "platformFee"), numberStyle);
            setCurrencyCell(row.createCell(5), getRawDecimalOrZero(d.getRawData(), "monthlyRentCode"), numberStyle);
            row.createCell(6).setCellValue(getRawDecimalOrZero(d.getRawData(), "domesticDuration").doubleValue());
            row.createCell(7).setCellValue(getRawDecimalOrZero(d.getRawData(), "transferDuration").doubleValue());
            setCurrencyCell(row.createCell(8), getRawDecimalOrZero(d.getRawData(), "domesticFee"), numberStyle);
            row.createCell(9).setCellValue(getRawDecimalOrZero(d.getRawData(), "internationalDuration").doubleValue());
            setCurrencyCell(row.createCell(10), getRawDecimalOrZero(d.getRawData(), "internationalFee"), numberStyle);

            BigDecimal subtotal = getRawDecimalOrZero(d.getRawData(), "platformFee")
                    .add(getRawDecimalOrZero(d.getRawData(), "monthlyRentCode"))
                    .add(getRawDecimalOrZero(d.getRawData(), "domesticFee"))
                    .add(getRawDecimalOrZero(d.getRawData(), "internationalFee"));
            setCurrencyCell(row.createCell(11), subtotal, numberStyle);

            String remark = "";
            if (d.getIsException() != null && d.getIsException() == 1) remark = "例外";
            if (d.getIsSeconded() != null && d.getIsSeconded() == 1)
                remark = remark.isEmpty() ? "借调" : remark + "/借调";
            row.createCell(12).setCellValue(remark);
        }
        autoSizeColumns(sheet, headers.length);
    }

    private void writeRecordingSheet(XSSFWorkbook wb, String sheetPrefix,
                                     List<BillDetail> details,
                                     Map<Long, SysOrganization> orgMap,
                                     String pathPrefix,
                                     CellStyle headerStyle, CellStyle numberStyle) {
        Sheet sheet = wb.createSheet(sheetPrefix + "_录音费用");
        String[] headers = {"一级分行", "部门代码", "部门名称", "分机号", "号码", "录音目录", "费用小计(单位：元)"};
        writeHeaderRow(sheet, headers, headerStyle);

        List<BillDetail> recDetails = details.stream()
                .filter(d -> "RECORDING".equals(d.getSheetType()))
                .filter(d -> isInPath(d.getOrgId(), pathPrefix, orgMap))
                .collect(Collectors.toList());

        int rowIdx = 1;
        for (BillDetail d : recDetails) {
            Row row = sheet.createRow(rowIdx++);
            SysOrganization org = d.getOrgId() != null ? orgMap.get(d.getOrgId()) : null;
            row.createCell(0).setCellValue(findBranchName(d.getOrgId(), orgMap));
            row.createCell(1).setCellValue(org != null && org.getCode() != null ? org.getCode() : "");
            row.createCell(2).setCellValue(org != null ? org.getName() : "");
            row.createCell(3).setCellValue(d.getExtension() != null ? d.getExtension() : "");
            row.createCell(4).setCellValue(d.getPhoneNumber());
            row.createCell(5).setCellValue(getRawString(d.getRawData(), "recordingDir"));
            setCurrencyCell(row.createCell(6), d.getRecordingFee(), numberStyle);
        }
        autoSizeColumns(sheet, headers.length);
    }

    private void writeFlashSheet(XSSFWorkbook wb, String sheetPrefix,
                                 List<BillDetail> details,
                                 Map<Long, SysOrganization> orgMap,
                                 String pathPrefix,
                                 CellStyle headerStyle, CellStyle numberStyle) {
        Sheet sheet = wb.createSheet(sheetPrefix + "_闪信费用");
        String[] headers = {"一级分行", "部门代码", "部门名称", "号码", "月份", "下发量", "金额"};
        writeHeaderRow(sheet, headers, headerStyle);

        List<BillDetail> flashDetails = details.stream()
                .filter(d -> "FLASH_MSG".equals(d.getSheetType()))
                .filter(d -> isInPath(d.getOrgId(), pathPrefix, orgMap))
                .collect(Collectors.toList());

        int rowIdx = 1;
        for (BillDetail d : flashDetails) {
            Row row = sheet.createRow(rowIdx++);
            SysOrganization org = d.getOrgId() != null ? orgMap.get(d.getOrgId()) : null;
            row.createCell(0).setCellValue(findBranchName(d.getOrgId(), orgMap));
            row.createCell(1).setCellValue(org != null && org.getCode() != null ? org.getCode() : "");
            row.createCell(2).setCellValue(org != null ? org.getName() : "");
            row.createCell(3).setCellValue(d.getPhoneNumber());
            row.createCell(4).setCellValue(d.getFlashMonth() != null ? d.getFlashMonth() : "");
            row.createCell(5).setCellValue(getRawDecimalOrZero(d.getRawData(), "flashCount").doubleValue());
            setCurrencyCell(row.createCell(6), d.getFlashMsgFee(), numberStyle);
        }
        autoSizeColumns(sheet, headers.length);
    }

    private void writeCrbtSheet(XSSFWorkbook wb, String sheetPrefix,
                                List<BillDetail> details,
                                Map<Long, SysOrganization> orgMap,
                                String pathPrefix,
                                CellStyle headerStyle, CellStyle numberStyle) {
        Sheet sheet = wb.createSheet(sheetPrefix + "_彩铃费用");
        String[] headers = {"一级分行", "部门代码", "部门名称", "分机号", "号码", "费用"};
        writeHeaderRow(sheet, headers, headerStyle);

        List<BillDetail> crbtDetails = details.stream()
                .filter(d -> "CRBT".equals(d.getSheetType()))
                .filter(d -> isInPath(d.getOrgId(), pathPrefix, orgMap))
                .collect(Collectors.toList());

        int rowIdx = 1;
        for (BillDetail d : crbtDetails) {
            Row row = sheet.createRow(rowIdx++);
            SysOrganization org = d.getOrgId() != null ? orgMap.get(d.getOrgId()) : null;
            row.createCell(0).setCellValue(findBranchName(d.getOrgId(), orgMap));
            row.createCell(1).setCellValue(org != null && org.getCode() != null ? org.getCode() : "");
            row.createCell(2).setCellValue(org != null ? org.getName() : "");
            row.createCell(3).setCellValue(d.getExtension() != null ? d.getExtension() : "");
            row.createCell(4).setCellValue(d.getPhoneNumber());
            setCurrencyCell(row.createCell(5), d.getCrbtFee(), numberStyle);
        }
        autoSizeColumns(sheet, headers.length);
    }

    // ==================== Cost Center Mapping (unchanged) ====================

    public byte[] exportCostCenterMapping(Long batchId, Long branchOrgId, Long operatorId) throws IOException {
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();

        String branchPath = branchOrgId != null && orgMap.containsKey(branchOrgId)
                ? orgMap.get(branchOrgId).getPath() : null;
        List<BillDetail> details = filterDetailsByPath(allDetails, branchPath, orgMap);

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
            auditLog(operatorId, "EXPORT_COST_CENTER_MAPPING", batchId,
                    "{\"branch_org_id\":" + (branchOrgId != null ? branchOrgId : "null") + "}");
            return out.toByteArray();
        }
    }

    // ==================== Fee Aggregation Helpers ====================

    /** Aggregate raw_data fields from bill_detail for a given org path prefix */
    private AggregatedFees aggregateFeesByOrgPath(Long batchId, String pathPrefix,
                                                   Map<Long, SysOrganization> orgMap) {
        List<BillDetail> callDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId)
                .stream()
                .filter(d -> "CALL".equals(d.getSheetType())
                        && isInPath(d.getOrgId(), pathPrefix, orgMap))
                .collect(Collectors.toList());

        AggregatedFees fees = new AggregatedFees();
        for (BillDetail d : callDetails) {
            fees.platformFee = safeAdd(fees.platformFee, getRawDecimalOrZero(d.getRawData(), "platformFee"));
            fees.monthlyRentCode = safeAdd(fees.monthlyRentCode, getRawDecimalOrZero(d.getRawData(), "monthlyRentCode"));
            fees.domesticDuration = safeAdd(fees.domesticDuration, getRawDecimalOrZero(d.getRawData(), "domesticDuration"));
            fees.transferDuration = safeAdd(fees.transferDuration, getRawDecimalOrZero(d.getRawData(), "transferDuration"));
            fees.domesticFee = safeAdd(fees.domesticFee, getRawDecimalOrZero(d.getRawData(), "domesticFee"));
            fees.internationalDuration = safeAdd(fees.internationalDuration, getRawDecimalOrZero(d.getRawData(), "internationalDuration"));
            fees.internationalFee = safeAdd(fees.internationalFee, getRawDecimalOrZero(d.getRawData(), "internationalFee"));
        }
        return fees;
    }

    /** Aggregate raw_data fields from bill_detail for a specific org_id */
    private AggregatedFees aggregateFeesByOrgId(Long batchId, Long orgId,
                                                 Map<Long, SysOrganization> orgMap) {
        List<BillDetail> callDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId)
                .stream()
                .filter(d -> "CALL".equals(d.getSheetType()) && Objects.equals(d.getOrgId(), orgId))
                .collect(Collectors.toList());

        AggregatedFees fees = new AggregatedFees();
        for (BillDetail d : callDetails) {
            fees.platformFee = safeAdd(fees.platformFee, getRawDecimalOrZero(d.getRawData(), "platformFee"));
            fees.monthlyRentCode = safeAdd(fees.monthlyRentCode, getRawDecimalOrZero(d.getRawData(), "monthlyRentCode"));
            fees.domesticFee = safeAdd(fees.domesticFee, getRawDecimalOrZero(d.getRawData(), "domesticFee"));
            fees.internationalFee = safeAdd(fees.internationalFee, getRawDecimalOrZero(d.getRawData(), "internationalFee"));
        }
        return fees;
    }

    private static class AggregatedFees {
        BigDecimal platformFee = ZERO;
        BigDecimal monthlyRentCode = ZERO;
        BigDecimal domesticDuration = ZERO;
        BigDecimal transferDuration = ZERO;
        BigDecimal domesticFee = ZERO;
        BigDecimal internationalDuration = ZERO;
        BigDecimal internationalFee = ZERO;
    }

    // ==================== Org Hierarchy Helpers ====================

    private boolean isInPath(Long orgId, String pathPrefix, Map<Long, SysOrganization> orgMap) {
        if (orgId == null || pathPrefix == null || pathPrefix.isEmpty()) return true; // no filter
        SysOrganization org = orgMap.get(orgId);
        return org != null && org.getPath() != null && org.getPath().startsWith(pathPrefix);
    }

    private List<BillDetail> filterDetailsByPath(List<BillDetail> details, String pathPrefix,
                                                  Map<Long, SysOrganization> orgMap) {
        if (pathPrefix == null || pathPrefix.isEmpty()) return details;
        return details.stream()
                .filter(d -> isInPath(d.getOrgId(), pathPrefix, orgMap))
                .collect(Collectors.toList());
    }

    private SysOrganization findBranchOrg(Long orgId, Map<Long, SysOrganization> orgMap) {
        if (orgId == null) return null;
        Set<Long> visited = new HashSet<>();
        SysOrganization org = orgMap.get(orgId);
        while (org != null && !visited.contains(org.getId())) {
            if (org.getType() != null && org.getType() == 2) return org;
            if (org.getType() != null && org.getType() == 1) return null;
            visited.add(org.getId());
            if (org.getParentId() == null || org.getParentId() == 0L) break;
            org = orgMap.get(org.getParentId());
        }
        return null;
    }

    private String findBranchName(Long orgId, Map<Long, SysOrganization> orgMap) {
        SysOrganization branch = findBranchOrg(orgId, orgMap);
        if (branch != null) return branch.getName();
        SysOrganization org = orgId != null ? orgMap.get(orgId) : null;
        return org != null ? org.getName() : "";
    }

    private String findParentBranchName(Long orgId, Map<Long, SysOrganization> orgMap) {
        SysOrganization org = orgMap.get(orgId);
        if (org == null) return "";
        SysOrganization parent = orgMap.get(org.getParentId());
        if (parent != null && parent.getType() != null && parent.getType() == 2) return parent.getName();
        return findBranchName(orgId, orgMap);
    }

    private String buildFullNamePath(Long orgId, Map<Long, SysOrganization> orgMap) {
        if (orgId == null) return "";
        List<String> names = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        SysOrganization org = orgMap.get(orgId);
        while (org != null && !visited.contains(org.getId())) {
            if (org.getType() != null && org.getType() == 1) break;
            names.add(0, org.getName());
            visited.add(org.getId());
            if (org.getParentId() == null || org.getParentId() == 0L) break;
            org = orgMap.get(org.getParentId());
        }
        return String.join("/", names);
    }

    private String formatMonthLabel(String billingMonth) {
        if (billingMonth == null || billingMonth.isEmpty()) return "";
        try {
            String[] parts = billingMonth.split("-");
            if (parts.length >= 2) return parts[0] + "年" + Integer.parseInt(parts[1]) + "月";
        } catch (NumberFormatException ignored) {}
        return billingMonth;
    }

    private static String orgTypeLabel(Byte type) {
        if (type == null) return "未知";
        return switch (type.intValue()) {
            case 1 -> "集团";
            case 2 -> "一级分行";
            case 3 -> "二级分行";
            case 4 -> "部门";
            case 5 -> "综合支行";
            case 6 -> "零专支行";
            default -> "其他(" + type + ")";
        };
    }

    private SysOrganization orgMapGet(Long id) {
        if (id == null) return null;
        return buildOrgMap().get(id);
    }

    // ==================== Raw Data JSON Helpers ====================

    private BigDecimal getRawDecimal(String rawData, String field) {
        if (rawData == null || rawData.isEmpty() || rawData.equals("{}")) return null;
        try {
            Map<String, Object> map = MAPPER.readValue(rawData, new TypeReference<Map<String, Object>>() {});
            Object val = map.get(field);
            if (val == null) return null;
            if (val instanceof Number) return BigDecimal.valueOf(((Number) val).doubleValue());
            if (val instanceof String) { String s = ((String) val).trim(); return s.isEmpty() ? null : new BigDecimal(s); }
            return null;
        } catch (Exception e) { return null; }
    }

    private BigDecimal getRawDecimalOrZero(String rawData, String field) {
        BigDecimal val = getRawDecimal(rawData, field);
        return val != null ? val : ZERO;
    }

    private String getRawString(String rawData, String field) {
        if (rawData == null || rawData.isEmpty() || rawData.equals("{}")) return "";
        try {
            Map<String, Object> map = MAPPER.readValue(rawData, new TypeReference<Map<String, Object>>() {});
            Object val = map.get(field);
            return val != null ? val.toString() : "";
        } catch (Exception e) { return ""; }
    }

    // ==================== Style Factories ====================

    private static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont(); font.setBold(true); style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN); style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN); style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createNumberStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0.00"));
        return style;
    }

    private static CellStyle createBoldStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont(); font.setBold(true); style.setFont(font);
        return style;
    }

    // ==================== Utility Methods ====================

    private void writeHeaderRow(Sheet sheet, String[] headers, CellStyle headerStyle) {
        Row hr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell c = hr.createCell(i); c.setCellValue(headers[i]); c.setCellStyle(headerStyle);
        }
    }

    private static void setCurrencyCell(Cell cell, BigDecimal value, CellStyle numberStyle) {
        double v = value != null ? value.doubleValue() : 0;
        cell.setCellValue(v); cell.setCellStyle(numberStyle);
    }

    private static String confirmStatusLabel(Byte status) {
        if (status == null) return "未知";
        return switch (status.intValue()) {
            case 0 -> "待确认"; case 1 -> "已确认"; case 2 -> "已撤回"; default -> "未知";
        };
    }

    private static <T> BigDecimal safeSum(List<T> items, java.util.function.Function<T, BigDecimal> getter) {
        return items.stream().map(getter).filter(Objects::nonNull)
                .reduce(ZERO, BigDecimal::add);
    }

    private static void autoSizeColumns(Sheet sheet, int colCount) {
        for (int i = 0; i < colCount; i++) {
            sheet.autoSizeColumn(i);
            if (sheet.getColumnWidth(i) < 2000) sheet.setColumnWidth(i, 2000);
        }
    }

    private Map<Long, SysOrganization> buildOrgMap() {
        return orgRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() == null)
                .collect(Collectors.toMap(SysOrganization::getId, o -> o));
    }

    private static BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        return (a != null ? a : ZERO).add(b != null ? b : ZERO);
    }

    private void auditLog(Long operatorId, String action, Long targetId, String extra) {
        auditLogService.log(
                operatorId != null ? operatorId : 0L, "user",
                action, "bill_batch", targetId, extra);
    }
}

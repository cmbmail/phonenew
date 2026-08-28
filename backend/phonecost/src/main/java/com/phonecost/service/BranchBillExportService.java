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
    private final PhoneOwnershipEntryRepository phoneOwnershipEntryRepository;
    private final AllocationOrgEntryRepository allocationOrgEntryRepository;
    private final AllocationDeptEntryRepository allocationDeptEntryRepository;
    private final RecordingDataEntryRepository recordingDataEntryRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** Build org map — fresh query each time, no shared mutable state (fixes concurrency bug with volatile instance cache) */
    private Map<Long, SysOrganization> buildOrgMap() {
        return orgRepository.findByDeletedAtIsNull().stream()
                .collect(Collectors.toMap(SysOrganization::getId, o -> o));
    }

    // ==================== L1: 分摊汇总（集团→一级分行） ====================

    /**
     * 导出L1分摊汇总表：每个一级分行一行，汇总其所有下属组织的费用
     */
    public byte[] exportLevel1Summary(Long batchId, Long operatorId) throws IOException {

        BillBatch batch = billBatchRepository.findByIdAndDeletedAtIsNull(batchId)
                .orElseThrow(() -> new IllegalArgumentException("账单批次不存在: " + batchId));
        List<AllocationResult> allResults = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();
        Map<Long, List<BillDetail>> groupedByOrgId = groupCallDetailsByOrgId(allDetails);

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
                row.createCell(1).setCellValue(branch.getCostCenter() != null ? branch.getCostCenter() : "");

                // 从bill_detail按原始列聚合费用（需要raw_data）
                AggregatedFees fees = aggregateFeesByOrgPath(groupedByOrgId, branchPath, orgMap);

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

            log.info("L1 summary exported: batch={}, branches={}", batchId, branches.size());
            return out.toByteArray();
        }
    }

    // ==================== L2: 一级分行明细（一级分行→直属下级） ====================

    /**
     * 导出L2一级分行明细：某一级分行下所有直属子组织（二级分行+部门+支行）的费用
     */
    public byte[] exportLevel2BranchDetail(Long batchId, Long branchOrgId, Long operatorId) throws IOException {

        BillBatch batch = billBatchRepository.findByIdAndDeletedAtIsNull(batchId)
                .orElseThrow(() -> new IllegalArgumentException("账单批次不存在: " + batchId));
        SysOrganization branch = orgMapGet(branchOrgId);
        if (branch == null) throw new IllegalArgumentException("组织不存在: " + branchOrgId);

        List<AllocationResult> allResults = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();
        Map<Long, List<BillDetail>> groupedByOrgId = groupCallDetailsByOrgId(allDetails);

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
                    allDetails, orgMap, groupedByOrgId, headerStyle, numberStyle, boldStyle);

            // Sheet2: 按号码费用（该分行下所有CALL明细）
            writePhoneDetailSheet(wb, monthLabel + branchName, allDetails, orgMap,
                    branchPath, headerStyle, numberStyle);

            // Sheet3-5: 录音/闪信/彩铃
            writeRecordingSheet(wb, monthLabel + branchName, allDetails, orgMap,
                    branchPath, headerStyle, numberStyle, batch.getBillingMonth());
            writeFlashSheet(wb, monthLabel + branchName, allDetails, orgMap,
                    branchPath, headerStyle, numberStyle);
            writeCrbtSheet(wb, monthLabel + branchName, allDetails, orgMap,
                    branchPath, headerStyle, numberStyle);

            wb.write(out);
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

        BillBatch batch = billBatchRepository.findByIdAndDeletedAtIsNull(batchId)
                .orElseThrow(() -> new IllegalArgumentException("账单批次不存在: " + batchId));
        SysOrganization subBranch = orgMapGet(subBranchOrgId);
        if (subBranch == null) throw new IllegalArgumentException("组织不存在: " + subBranchOrgId);

        List<AllocationResult> allResults = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();
        Map<Long, List<BillDetail>> groupedByOrgId = groupCallDetailsByOrgId(allDetails);

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
                    directChildren, allResults, allDetails, orgMap, groupedByOrgId, headerStyle, numberStyle, boldStyle);

            // Sheet2: 按号码费用
            writePhoneDetailSheet(wb, monthLabel + subBranchName, allDetails, orgMap,
                    subBranchPath, headerStyle, numberStyle);

            // Sheet3-5: 录音/闪信/彩铃
            writeRecordingSheet(wb, monthLabel + subBranchName, allDetails, orgMap,
                    subBranchPath, headerStyle, numberStyle, batch.getBillingMonth());
            writeFlashSheet(wb, monthLabel + subBranchName, allDetails, orgMap,
                    subBranchPath, headerStyle, numberStyle);
            writeCrbtSheet(wb, monthLabel + subBranchName, allDetails, orgMap,
                    subBranchPath, headerStyle, numberStyle);

            wb.write(out);
            log.info("L3 sub-branch detail exported: batch={}, subBranch={}, children={}",
                    batchId, subBranchName, directChildren.size());
            return out.toByteArray();
        }
    }

    // ==================== L2 Summary Sheet ====================

    private void writeL2SummarySheet(XSSFWorkbook wb, String monthLabel, String branchName,
                                       List<SysOrganization> children,
                                       List<AllocationResult> allResults,
                                       List<BillDetail> allDetails,
                                       Map<Long, SysOrganization> orgMap,
                                       Map<Long, List<BillDetail>> groupedByOrgId,
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
            row.createCell(3).setCellValue(child.getCostCenter() != null ? child.getCostCenter() : "");

            // 从bill_detail聚合原始费用
            AggregatedFees fees = aggregateFeesByOrgId(groupedByOrgId, child.getId());
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
                                     List<BillDetail> allDetails,
                                     Map<Long, SysOrganization> orgMap,
                                     Map<Long, List<BillDetail>> groupedByOrgId,
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
            row.createCell(3).setCellValue(child.getCostCenter() != null ? child.getCostCenter() : "");

            AggregatedFees fees = aggregateFeesByOrgId(groupedByOrgId, child.getId());
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
        String[] headers = {"一级分行", "部门代码", "部门名称", "号码",
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

            Map<String, Object> parsed = parseRawData(d.getRawData());
            setCurrencyCell(row.createCell(4), getRawDecimalOrZero(parsed, "platformFee"), numberStyle);
            setCurrencyCell(row.createCell(5), getRawDecimalOrZero(parsed, "monthlyRentCode"), numberStyle);
            row.createCell(6).setCellValue(getRawDecimalOrZero(parsed, "domesticDuration").doubleValue());
            row.createCell(7).setCellValue(getRawDecimalOrZero(parsed, "transferDuration").doubleValue());
            setCurrencyCell(row.createCell(8), getRawDecimalOrZero(parsed, "domesticFee"), numberStyle);
            row.createCell(9).setCellValue(getRawDecimalOrZero(parsed, "internationalDuration").doubleValue());
            setCurrencyCell(row.createCell(10), getRawDecimalOrZero(parsed, "internationalFee"), numberStyle);

            BigDecimal subtotal = getRawDecimalOrZero(parsed, "platformFee")
                    .add(getRawDecimalOrZero(parsed, "monthlyRentCode"))
                    .add(getRawDecimalOrZero(parsed, "domesticFee"))
                    .add(getRawDecimalOrZero(parsed, "internationalFee"));
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
                                     CellStyle headerStyle, CellStyle numberStyle,
                                     String billingMonth) {
        Sheet sheet = wb.createSheet(sheetPrefix + "_录音费用");
        String[] headers = {"一级分行", "部门代码", "部门名称", "分机号", "号码", "录音目录", "费用小计(单位：元)"};
        writeHeaderRow(sheet, headers, headerStyle);

        Map<String, String> recordingDeptMap = buildRecordingDeptMap(billingMonth);

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
            Map<String, Object> parsed = parseRawData(d.getRawData());
            // Prefer dept_name from recording_data_entry (by phone number), fallback to raw recordingDir
            String recDir = d.getPhoneNumber() != null
                    ? recordingDeptMap.getOrDefault(d.getPhoneNumber(), getRawString(parsed, "recordingDir"))
                    : getRawString(parsed, "recordingDir");
            row.createCell(5).setCellValue(recDir);
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
            Map<String, Object> parsed = parseRawData(d.getRawData());
            row.createCell(5).setCellValue(getRawDecimalOrZero(parsed, "flashCount").doubleValue());
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
                row.createCell(4).setCellValue(org != null && org.getCostCenter() != null ? org.getCostCenter() : "");
                row.createCell(5).setCellValue(d.getIsException() != null && d.getIsException() == 1 ? "是" : "否");
                String remark = "";
                if (d.getIsSeconded() != null && d.getIsSeconded() == 1) remark = "借调";
                row.createCell(6).setCellValue(remark);
            }
            autoSizeColumns(sheet, headers.length);
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ==================== Fee Aggregation Helpers ====================

    /** Aggregate raw_data fields from bill_detail for a given org path prefix (uses pre-loaded details) */
    private AggregatedFees aggregateFeesByOrgPath(Map<Long, List<BillDetail>> groupedByOrgId, String pathPrefix,
                                                   Map<Long, SysOrganization> orgMap) {
        List<Long> matchingOrgIds = orgMap.values().stream()
                .filter(o -> o.getPath() != null && o.getPath().startsWith(pathPrefix))
                .map(SysOrganization::getId)
                .collect(Collectors.toList());

        AggregatedFees fees = new AggregatedFees();
        for (Long orgId : matchingOrgIds) {
            List<BillDetail> details = groupedByOrgId.get(orgId);
            if (details == null) continue;
            for (BillDetail d : details) {
                Map<String, Object> parsed = parseRawData(d.getRawData());
                fees.platformFee = safeAdd(fees.platformFee, getRawDecimalOrZero(parsed, "platformFee"));
                fees.monthlyRentCode = safeAdd(fees.monthlyRentCode, getRawDecimalOrZero(parsed, "monthlyRentCode"));
                fees.domesticDuration = safeAdd(fees.domesticDuration, getRawDecimalOrZero(parsed, "domesticDuration"));
                fees.transferDuration = safeAdd(fees.transferDuration, getRawDecimalOrZero(parsed, "transferDuration"));
                fees.domesticFee = safeAdd(fees.domesticFee, getRawDecimalOrZero(parsed, "domesticFee"));
                fees.internationalDuration = safeAdd(fees.internationalDuration, getRawDecimalOrZero(parsed, "internationalDuration"));
                fees.internationalFee = safeAdd(fees.internationalFee, getRawDecimalOrZero(parsed, "internationalFee"));
            }
        }
        return fees;
    }

    /** Aggregate raw_data fields from bill_detail for a specific org_id (uses pre-loaded details) */
    private AggregatedFees aggregateFeesByOrgId(Map<Long, List<BillDetail>> groupedByOrgId, Long orgId) {
        List<BillDetail> callDetails = groupedByOrgId.getOrDefault(orgId, Collections.emptyList());

        AggregatedFees fees = new AggregatedFees();
        for (BillDetail d : callDetails) {
            Map<String, Object> parsed = parseRawData(d.getRawData());
            fees.platformFee = safeAdd(fees.platformFee, getRawDecimalOrZero(parsed, "platformFee"));
            fees.monthlyRentCode = safeAdd(fees.monthlyRentCode, getRawDecimalOrZero(parsed, "monthlyRentCode"));
            fees.domesticFee = safeAdd(fees.domesticFee, getRawDecimalOrZero(parsed, "domesticFee"));
            fees.internationalFee = safeAdd(fees.internationalFee, getRawDecimalOrZero(parsed, "internationalFee"));
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

    // ==================== L1 Detail JSON (for frontend 分摊明细 tabs) ====================

    /**
     * Returns bill_detail rows for a given batchId and sheetType, with org_name resolved.
     * Each row is a Map with phone_number, org_name, ownership_source, and fields from raw_data.
     */
    public List<Map<String, Object>> getL1DetailData(Long batchId, String sheetType) {

        String upperSheetType = sheetType.toUpperCase();
        String billingMonth = getBillingMonthFromBatchId(batchId);
        List<BillDetail> details = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId)
                .stream()
                .filter(d -> upperSheetType.equals(d.getSheetType()))
                .collect(Collectors.toList());

        Map<Long, SysOrganization> orgMap = buildOrgMap();
        return buildDetailRows(details, upperSheetType, orgMap, billingMonth);
    }

    /**
     * Returns bill_detail rows for a given batchId, branchOrgId and sheetType,
     * filtered to only include details belonging to the branch's org subtree.
     */
    public List<Map<String, Object>> getL2DetailData(Long batchId, Long branchOrgId, String sheetType) {

        String upperSheetType = sheetType.toUpperCase();
        String billingMonth = getBillingMonthFromBatchId(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();
        SysOrganization branch = orgMap.get(branchOrgId);
        if (branch == null) return Collections.emptyList();
        String pathPrefix = branch.getPath();

        List<BillDetail> details = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId)
                .stream()
                .filter(d -> upperSheetType.equals(d.getSheetType()))
                .filter(d -> isInPath(d.getOrgId(), pathPrefix, orgMap))
                .collect(Collectors.toList());

        return buildDetailRows(details, upperSheetType, orgMap, billingMonth);
    }

    /**
     * Returns bill_detail rows for a given batchId, subBranchOrgId and sheetType,
     * filtered to only include details belonging to the sub-branch's org subtree.
     */
    public List<Map<String, Object>> getL3DetailData(Long batchId, Long subBranchOrgId, String sheetType) {

        String upperSheetType = sheetType.toUpperCase();
        String billingMonth = getBillingMonthFromBatchId(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();
        SysOrganization subBranch = orgMap.get(subBranchOrgId);
        if (subBranch == null) return Collections.emptyList();
        String pathPrefix = subBranch.getPath();

        List<BillDetail> details = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId)
                .stream()
                .filter(d -> upperSheetType.equals(d.getSheetType()))
                .filter(d -> isInPath(d.getOrgId(), pathPrefix, orgMap))
                .collect(Collectors.toList());

        return buildDetailRows(details, upperSheetType, orgMap, billingMonth);
    }

    /** Shared helper to build detail row maps from a filtered list of BillDetail */
    private List<Map<String, Object>> buildDetailRows(List<BillDetail> details, String sheetType,
                                                       Map<Long, SysOrganization> orgMap,
                                                       String billingMonth) {
        Map<String, String> recordingDeptMap = "RECORDING".equals(sheetType) ? buildRecordingDeptMap(billingMonth) : null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BillDetail d : details) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("org_id", d.getOrgId() != null ? d.getOrgId() : -1L);
            row.put("phone_number", d.getPhoneNumber());
            row.put("org_name", buildFullNamePath(d.getOrgId(), orgMap));
            SysOrganization org = orgMap.get(d.getOrgId());
            row.put("org_code", org != null && org.getCode() != null ? org.getCode() : "");
            row.put("cost_center", org != null && org.getCostCenter() != null ? org.getCostCenter() : "");
            row.put("ownership_source", d.getOwnershipSource() != null ? d.getOwnershipSource() : "");

            String raw = d.getRawData();
            Map<String, Object> parsed = parseRawData(raw);

            switch (sheetType) {
                case "CALL" -> {
                    row.put("extension", d.getExtension() != null ? d.getExtension() : "");
                    row.put("platform_fee", getRawDecimalOrZero(parsed, "platformFee"));
                    row.put("monthly_rent_code", getRawDecimalOrZero(parsed, "monthlyRentCode"));
                    row.put("domestic_duration", getRawDecimalOrZero(parsed, "domesticDuration"));
                    row.put("transfer_duration", getRawDecimalOrZero(parsed, "transferDuration"));
                    row.put("domestic_fee", getRawDecimalOrZero(parsed, "domesticFee"));
                    row.put("international_duration", getRawDecimalOrZero(parsed, "internationalDuration"));
                    row.put("international_fee", getRawDecimalOrZero(parsed, "internationalFee"));
                    row.put("total_fee", getRawDecimalOrZero(parsed, "totalFee"));
                }
                case "RECORDING" -> {
                    row.put("extension", d.getExtension() != null ? d.getExtension() : "");
                    // Prefer dept_name from recording_data_entry (by phone number), fallback to raw recordingDir
                    String recDir = (recordingDeptMap != null && d.getPhoneNumber() != null)
                            ? recordingDeptMap.getOrDefault(d.getPhoneNumber(), getRawString(parsed, "recordingDir"))
                            : getRawString(parsed, "recordingDir");
                    row.put("recording_dir", recDir);
                    row.put("recording_fee", getRawDecimalOrZero(parsed, "recordingFee"));
                }
                case "CRBT" -> {
                    row.put("extension", d.getExtension() != null ? d.getExtension() : "");
                    row.put("crbt_fee", getRawDecimalOrZero(parsed, "crbtFee"));
                }
                case "FLASH_MSG" -> {
                    row.put("extension", d.getExtension() != null ? d.getExtension() : "");
                    row.put("flash_month", d.getFlashMonth() != null ? d.getFlashMonth() : "");
                    row.put("flash_count", getRawDecimalOrZero(parsed, "flashCount"));
                    row.put("flash_msg_fee", getRawDecimalOrZero(parsed, "flashMsgFee"));
                }
                default -> { /* no extra fields */ }
            }

            rows.add(row);
        }
        return rows;
    }

    // ==================== L1 Summary JSON (for frontend table) ====================

    public List<Map<String, Object>> getL1SummaryData(Long batchId) {

        List<AllocationResult> allResults = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = buildOrgMap();
        Map<Long, List<BillDetail>> groupedByOrgId = groupCallDetailsByOrgId(allDetails);

        List<SysOrganization> branches = orgMap.values().stream()
                .filter(o -> o.getType() != null && o.getType() == 2 && o.getDeletedAt() == null)
                .sorted(Comparator.comparing(SysOrganization::getId))
                .collect(Collectors.toList());

        List<Map<String, Object>> rows = new ArrayList<>();

        for (SysOrganization branch : branches) {
            String branchPath = branch.getPath();

            AggregatedFees fees = aggregateFeesByOrgPath(groupedByOrgId, branchPath, orgMap);

            List<AllocationResult> childResults = allResults.stream()
                    .filter(r -> {
                        if (r.getOrgId() == null || r.getOrgId() == -1L) return false;
                        SysOrganization rOrg = orgMap.get(r.getOrgId());
                        return rOrg != null && rOrg.getPath() != null
                                && rOrg.getPath().startsWith(branchPath);
                    })
                    .collect(Collectors.toList());

            BigDecimal sumRec = safeSum(childResults, AllocationResult::getRecordingFee);
            BigDecimal sumCrbt = safeSum(childResults, AllocationResult::getCrbtFee);
            BigDecimal sumFlash = safeSum(childResults, AllocationResult::getFlashMsgFee);
            int phoneCount = childResults.stream()
                    .mapToInt(r -> r.getPhoneCount() != null ? r.getPhoneCount() : 0).sum();
            int confirmed = (int) childResults.stream()
                    .filter(r -> r.getConfirmStatus() != null && r.getConfirmStatus() == 1).count();
            int pending = (int) childResults.stream()
                    .filter(r -> r.getConfirmStatus() != null && r.getConfirmStatus() == 0).count();

            BigDecimal callSubtotal = fees.platformFee.add(fees.monthlyRentCode)
                    .add(fees.domesticFee).add(fees.internationalFee);
            BigDecimal total = callSubtotal.add(sumRec).add(sumCrbt).add(sumFlash);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_id", branch.getId());
            row.put("branch_name", branch.getName());
            row.put("cost_center", branch.getCostCenter() != null ? branch.getCostCenter() : "");
            row.put("platform_fee", fees.platformFee);
            row.put("monthly_rent_code", fees.monthlyRentCode);
            row.put("domestic_duration", fees.domesticDuration);
            row.put("transfer_duration", fees.transferDuration);
            row.put("domestic_fee", fees.domesticFee);
            row.put("international_duration", fees.internationalDuration);
            row.put("international_fee", fees.internationalFee);
            row.put("call_subtotal", callSubtotal);
            row.put("recording_fee", sumRec);
            row.put("crbt_fee", sumCrbt);
            row.put("flash_fee", sumFlash);
            row.put("total_fee", total);
            row.put("phone_count", phoneCount);
            row.put("confirmed", confirmed);
            row.put("pending", pending);
            rows.add(row);
        }

        return rows;
    }

    // ==================== Ownership-based L1/L2/L3 Summary & Detail (data source: phone_ownership_entry) ====================

    /**
     * Get billing_month from a bill batchId.
     */
    private String getBillingMonthFromBatchId(Long batchId) {
        return billBatchRepository.findByIdAndDeletedAtIsNull(batchId)
                .map(b -> b.getBillingMonth())
                .orElse(null);
    }

    /**
     * Load ownership entries for a given billing_month (non-exception only).
     */
    private List<PhoneOwnershipEntry> loadOwnershipEntries(String billingMonth) {
        if (billingMonth == null || billingMonth.isBlank()) return Collections.emptyList();
        return phoneOwnershipEntryRepository.findAllByBillingMonth(billingMonth).stream()
                .filter(e -> e.getIsException() == null || e.getIsException() == 0)
                .collect(Collectors.toList());
    }

    /**
     * Build a phone→ownership map (latest entry per phone number wins).
     */
    private Map<String, PhoneOwnershipEntry> buildPhoneOwnershipMap(List<PhoneOwnershipEntry> entries) {
        Map<String, PhoneOwnershipEntry> map = new LinkedHashMap<>();
        // entries are ordered by id ASC; later entries overwrite earlier for same phone number
        for (PhoneOwnershipEntry e : entries) {
            if (e.getPhoneNumber() != null && !e.getPhoneNumber().isBlank()) {
                map.put(e.getPhoneNumber(), e);
            }
        }
        return map;
    }

    /**
     * Build a phone→allocation_org_entry map for a given billing_month.
     * Cross-month matching: loads all allocation_org_entry records ordered by month DESC,
     * takes the most recent month's value per phone number (same logic as sync-allocation-org button).
     * Used to source alloc_dept / org_code / cost_center for detail rows and L3 summary.
     */
    private Map<String, AllocationOrgEntry> buildAllocOrgMap(String billingMonth) {
        if (billingMonth == null || billingMonth.isBlank()) return Collections.emptyMap();
        Map<String, AllocationOrgEntry> map = new LinkedHashMap<>();
        // Load all months ordered DESC so the most recent month's data takes priority
        List<AllocationOrgEntry> entries = allocationOrgEntryRepository.findAllActiveOrderedByMonthDesc();
        for (AllocationOrgEntry e : entries) {
            String phone = e.getPhoneNumber();
            if (phone == null || phone.isEmpty()) continue;
            AllocationOrgEntry existing = map.get(phone);
            if (existing == null) {
                // First occurrence = most recent month's record
                map.put(phone, e);
            } else {
                // Supplement missing fields from earlier months
                boolean existingHasAlloc = existing.getAllocDept() != null && !existing.getAllocDept().isEmpty();
                boolean existingHasOrgCode = existing.getOrgCode() != null && !existing.getOrgCode().isEmpty();
                boolean existingHasCostCenter = existing.getCostCenter() != null && !existing.getCostCenter().isEmpty();
                if (!existingHasAlloc && e.getAllocDept() != null && !e.getAllocDept().isEmpty()) {
                    existing.setAllocDept(e.getAllocDept());
                }
                if (!existingHasOrgCode && e.getOrgCode() != null && !e.getOrgCode().isEmpty()) {
                    existing.setOrgCode(e.getOrgCode());
                }
                if (!existingHasCostCenter && e.getCostCenter() != null && !e.getCostCenter().isEmpty()) {
                    existing.setCostCenter(e.getCostCenter());
                }
            }
        }
        return map;
    }

    /**
     * Get distinct l1_branch names from ownership entries, sorted.
     */
    private List<String> getDistinctL1Branches(List<PhoneOwnershipEntry> entries) {
        return entries.stream()
                .map(PhoneOwnershipEntry::getL1Branch)
                .filter(b -> b != null && !b.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get distinct l2_branch names for a given l1_branch, sorted.
     */
    private List<String> getDistinctL2Branches(List<PhoneOwnershipEntry> entries, String l1Branch) {
        return entries.stream()
                .filter(e -> l1Branch == null || l1Branch.equals(e.getL1Branch()))
                .map(PhoneOwnershipEntry::getL2Branch)
                .filter(b -> b != null && !b.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get distinct alloc_dept names for a given l1_branch + l2_branch, sorted.
     */
    private List<String> getDistinctAllocDepts(List<PhoneOwnershipEntry> entries, String l1Branch, String l2Branch) {
        return entries.stream()
                .filter(e -> (l1Branch == null || l1Branch.equals(e.getL1Branch()))
                        && (l2Branch == null || l2Branch.equals(e.getL2Branch())))
                .map(PhoneOwnershipEntry::getAllocDept)
                .filter(d -> d != null && !d.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Aggregate bill_detail fees by phone numbers that match ownership entries for a given l1_branch.
     * Returns a Map with call fee fields (from raw_data JSON) + recording/crbt/flash fees (from entity fields).
     */
    private Map<String, BigDecimal> aggregateFeesByPhones(List<BillDetail> allDetails, Set<String> phoneNumbers) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal platformFee = ZERO, monthlyRentCode = ZERO, domesticFee = ZERO, internationalFee = ZERO;
        BigDecimal recordingFee = ZERO, crbtFee = ZERO, flashMsgFee = ZERO, totalFee = ZERO;
        BigDecimal domesticDuration = ZERO, transferDuration = ZERO, internationalDuration = ZERO;

        for (BillDetail d : allDetails) {
            if (d.getPhoneNumber() == null || !phoneNumbers.contains(d.getPhoneNumber())) continue;
            Map<String, Object> parsed = parseRawData(d.getRawData());
            String st = d.getSheetType();
            if (st == null) continue;
            switch (st) {
                case "CALL" -> {
                    platformFee = safeAdd(platformFee, getRawDecimalOrZero(parsed, "platformFee"));
                    monthlyRentCode = safeAdd(monthlyRentCode, getRawDecimalOrZero(parsed, "monthlyRentCode"));
                    domesticDuration = safeAdd(domesticDuration, getRawDecimalOrZero(parsed, "domesticDuration"));
                    transferDuration = safeAdd(transferDuration, getRawDecimalOrZero(parsed, "transferDuration"));
                    domesticFee = safeAdd(domesticFee, getRawDecimalOrZero(parsed, "domesticFee"));
                    internationalDuration = safeAdd(internationalDuration, getRawDecimalOrZero(parsed, "internationalDuration"));
                    internationalFee = safeAdd(internationalFee, getRawDecimalOrZero(parsed, "internationalFee"));
                    totalFee = safeAdd(totalFee, getRawDecimalOrZero(parsed, "totalFee"));
                }
                case "RECORDING" -> {
                    recordingFee = safeAdd(recordingFee, getRawDecimalOrZero(parsed, "recordingFee"));
                    totalFee = safeAdd(totalFee, getRawDecimalOrZero(parsed, "recordingFee"));
                }
                case "CRBT" -> {
                    crbtFee = safeAdd(crbtFee, getRawDecimalOrZero(parsed, "crbtFee"));
                    totalFee = safeAdd(totalFee, getRawDecimalOrZero(parsed, "crbtFee"));
                }
                case "FLASH_MSG" -> {
                    flashMsgFee = safeAdd(flashMsgFee, getRawDecimalOrZero(parsed, "flashMsgFee"));
                    totalFee = safeAdd(totalFee, getRawDecimalOrZero(parsed, "flashMsgFee"));
                }
            }
        }
        result.put("platform_fee", platformFee);
        result.put("monthly_rent_code", monthlyRentCode);
        result.put("domestic_duration", domesticDuration);
        result.put("transfer_duration", transferDuration);
        result.put("domestic_fee", domesticFee);
        result.put("international_duration", internationalDuration);
        result.put("international_fee", internationalFee);
        result.put("recording_fee", recordingFee);
        result.put("crbt_fee", crbtFee);
        result.put("flash_fee", flashMsgFee);
        result.put("total_fee", totalFee);
        BigDecimal callSubtotal = platformFee.add(monthlyRentCode).add(domesticFee).add(internationalFee);
        result.put("call_subtotal", callSubtotal);
        return result;
    }

    /**
     * Build a phone→recording dept_name map for a given billing_month from recording_data_entry.
     */
    private Map<String, String> buildRecordingDeptMap(String billingMonth) {
        if (billingMonth == null || billingMonth.isBlank()) return Collections.emptyMap();
        List<RecordingDataEntry> entries = recordingDataEntryRepository.findAllByBillingMonth(billingMonth).stream()
                .filter(e -> e.getPhoneNumber() != null && !e.getPhoneNumber().isBlank())
                .collect(Collectors.toList());
        Map<String, String> map = new LinkedHashMap<>();
        for (RecordingDataEntry e : entries) {
            if (e.getDeptName() != null && !e.getDeptName().isBlank()) {
                map.putIfAbsent(e.getPhoneNumber(), e.getDeptName());
            }
        }
        return map;
    }

    /**
     * Build a full_path → [branch, dept_name] map from allocation_dept_entry (all active entries).
     * Used to fallback l1_branch/l2_branch when phone_ownership_entry fields are empty.
     * Same logic as DataImportController export-ownership fallback.
     */
    private Map<String, String[]> buildAllocDeptMap() {
        Map<String, String[]> map = new HashMap<>();
        List<AllocationDeptEntry> entries = allocationDeptEntryRepository.findAllActiveEntries();
        for (AllocationDeptEntry ae : entries) {
            String fp = ae.getFullPath();
            if (fp != null && !fp.isEmpty() && !map.containsKey(fp)) {
                map.put(fp, new String[]{
                        ae.getBranch() != null ? ae.getBranch() : "",
                        ae.getDeptName() != null ? ae.getDeptName() : ""
                });
            }
        }
        return map;
    }

    /**
     * Resolve l1_branch for a phone ownership entry: prefer stored value, fallback to allocDeptMap by full_path,
     * then parse from full_path directly (e.g. 100014-广州分行-100282-代管零售银行部 → 广州分行).
     */
    private String resolveL1Branch(PhoneOwnershipEntry entry, Map<String, String[]> allocDeptMap) {
        String l1 = entry.getL1Branch();
        if (l1 != null && !l1.isEmpty()) return l1;
        String fp = entry.getFullPath();
        if (fp != null && !fp.isEmpty()) {
            String[] match = allocDeptMap.get(fp);
            if (match != null && match[0] != null && !match[0].isEmpty()) return match[0];
            // Fallback: parse branch name from full_path directly (e.g. 100006-太原分行-100454-审计部 → 太原分行)
            String parsed = parseBranchFromFullPath(fp);
            if (!parsed.isEmpty()) return parsed;
        }
        return "";
    }

    /**
     * Resolve l2_branch for a phone ownership entry: prefer stored value, fallback to allocDeptMap by full_path,
     * then parse from full_path directly (non-code, non-branch segments).
     */
    private String resolveL2Branch(PhoneOwnershipEntry entry, Map<String, String[]> allocDeptMap) {
        String l2 = entry.getL2Branch();
        if (l2 != null && !l2.isEmpty()) return l2;
        String fp = entry.getFullPath();
        if (fp != null && !fp.isEmpty()) {
            String[] match = allocDeptMap.get(fp);
            if (match != null && match[1] != null && !match[1].isEmpty()) return match[1];
            // Fallback: parse dept name from full_path directly (e.g. 100006-广州分行-100454-审计部 → 审计部)
            String parsed = parseDeptFromFullPath(fp);
            if (!parsed.isEmpty()) return parsed;
        }
        return "";
    }

    /**
     * Parse the branch (含"分行"的段) from a full_path like 100006-广州分行-100454-审计部.
     * Returns the first segment containing "分行" (e.g. 广州分行), or "" if none found.
     */
    private String parseBranchFromFullPath(String fullPath) {
        if (fullPath == null || fullPath.isBlank()) return "";
        String[] segments = fullPath.split("-");
        for (String seg : segments) {
            String trimmed = seg.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains("分行")) return trimmed;
        }
        return "";
    }

    /**
     * Parse the dept name from full_path like 100006-广州分行-100454-审计部.
     * Joins all non-code segments except the branch segment (e.g. 审计部).
     */
    private String parseDeptFromFullPath(String fullPath) {
        if (fullPath == null || fullPath.isBlank()) return "";
        String[] segments = fullPath.split("-");
        List<String> parts = new ArrayList<>();
        boolean branchSeen = false;
        for (String seg : segments) {
            String trimmed = seg.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains("分行")) {
                branchSeen = true;
                continue;
            }
            // Skip pure numeric code segments (e.g. 100006, 100454)
            if (trimmed.matches("\\d+")) continue;
            parts.add(trimmed);
        }
        // Only treat as dept when a branch was found in the path (avoid mis-parsing)
        return branchSeen ? String.join("-", parts) : "";
    }

    /**
     * L1 Summary from ownership data: one row per l1_branch, aggregated from bill_detail by phone number.
     * l1_branch is resolved from phone_ownership_entry, falling back to allocation_dept_entry by full_path.
     */
    public List<Map<String, Object>> getL1SummaryDataByOwnership(Long batchId) {
        String billingMonth = getBillingMonthFromBatchId(batchId);
        List<PhoneOwnershipEntry> ownershipEntries = loadOwnershipEntries(billingMonth);
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<String, String[]> allocDeptMap = buildAllocDeptMap();

        // Build phone → resolved l1_branch map
        Map<String, String> phoneToL1 = new LinkedHashMap<>();
        for (PhoneOwnershipEntry e : ownershipEntries) {
            String phone = e.getPhoneNumber();
            if (phone == null || phone.isBlank()) continue;
            String l1 = resolveL1Branch(e, allocDeptMap);
            if (!l1.isEmpty()) phoneToL1.putIfAbsent(phone, l1);
        }

        // Distinct l1_branches sorted
        List<String> l1Branches = phoneToL1.values().stream().distinct().sorted().collect(Collectors.toList());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String l1Branch : l1Branches) {
            Set<String> phones = phoneToL1.entrySet().stream()
                    .filter(e -> l1Branch.equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            Map<String, BigDecimal> fees = aggregateFeesByPhones(allDetails, phones);
            int phoneCount = phones.size();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("l1_branch", l1Branch);
            row.put("platform_fee", fees.get("platform_fee"));
            row.put("monthly_rent_code", fees.get("monthly_rent_code"));
            row.put("domestic_duration", fees.get("domestic_duration"));
            row.put("transfer_duration", fees.get("transfer_duration"));
            row.put("domestic_fee", fees.get("domestic_fee"));
            row.put("international_duration", fees.get("international_duration"));
            row.put("international_fee", fees.get("international_fee"));
            row.put("call_subtotal", fees.get("call_subtotal"));
            row.put("recording_fee", fees.get("recording_fee"));
            row.put("crbt_fee", fees.get("crbt_fee"));
            row.put("flash_fee", fees.get("flash_fee"));
            row.put("total_fee", fees.get("total_fee"));
            row.put("phone_count", phoneCount);
            rows.add(row);
        }
        return rows;
    }

    /**
     * L2 Summary from ownership data: one row per l2_branch under a given l1_branch.
     * l1_branch/l2_branch are resolved from phone_ownership_entry, falling back to allocation_dept_entry by full_path.
     */
    public List<Map<String, Object>> getL2SummaryDataByOwnership(Long batchId, String l1Branch) {
        String billingMonth = getBillingMonthFromBatchId(batchId);
        List<PhoneOwnershipEntry> ownershipEntries = loadOwnershipEntries(billingMonth);
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<String, String[]> allocDeptMap = buildAllocDeptMap();

        // Build phone → resolved (l1_branch, l2_branch) map, filtered by l1Branch
        Map<String, String> phoneToL2 = new LinkedHashMap<>();
        for (PhoneOwnershipEntry e : ownershipEntries) {
            String phone = e.getPhoneNumber();
            if (phone == null || phone.isBlank()) continue;
            String l1 = resolveL1Branch(e, allocDeptMap);
            if (!l1Branch.equals(l1)) continue;
            String l2 = resolveL2Branch(e, allocDeptMap);
            if (!l2.isEmpty()) phoneToL2.putIfAbsent(phone, l2);
        }

        // Distinct l2_branches sorted
        List<String> l2Branches = phoneToL2.values().stream().distinct().sorted().collect(Collectors.toList());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String l2Branch : l2Branches) {
            Set<String> phones = phoneToL2.entrySet().stream()
                    .filter(e -> l2Branch.equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            Map<String, BigDecimal> fees = aggregateFeesByPhones(allDetails, phones);
            int phoneCount = phones.size();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("l2_branch", l2Branch);
            row.put("l1_branch", l1Branch);
            row.put("platform_fee", fees.get("platform_fee"));
            row.put("monthly_rent_code", fees.get("monthly_rent_code"));
            row.put("domestic_duration", fees.get("domestic_duration"));
            row.put("transfer_duration", fees.get("transfer_duration"));
            row.put("domestic_fee", fees.get("domestic_fee"));
            row.put("international_duration", fees.get("international_duration"));
            row.put("international_fee", fees.get("international_fee"));
            row.put("call_subtotal", fees.get("call_subtotal"));
            row.put("recording_fee", fees.get("recording_fee"));
            row.put("crbt_fee", fees.get("crbt_fee"));
            row.put("flash_fee", fees.get("flash_fee"));
            row.put("total_fee", fees.get("total_fee"));
            row.put("phone_count", phoneCount);
            rows.add(row);
        }
        return rows;
    }

    /**
     * L3 Summary from ownership data: one row per alloc_dept under a given l1_branch + l2_branch.
     * alloc_dept is sourced from allocation_org_entry (cross-month), falling back to phone_ownership_entry.
     */
    public List<Map<String, Object>> getL3SummaryDataByOwnership(Long batchId, String l1Branch, String l2Branch) {
        String billingMonth = getBillingMonthFromBatchId(batchId);
        List<PhoneOwnershipEntry> ownershipEntries = loadOwnershipEntries(billingMonth);
        List<BillDetail> allDetails = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<String, AllocationOrgEntry> allocOrgMap = buildAllocOrgMap(billingMonth);

        // Build alloc_dept per phone: prefer allocation_org_entry, fallback to phone_ownership_entry
        Map<String, String> phoneAllocDept = new LinkedHashMap<>();
        for (PhoneOwnershipEntry e : ownershipEntries) {
            String phone = e.getPhoneNumber();
            if (phone == null || phone.isBlank()) continue;
            if (l1Branch != null && !l1Branch.equals(e.getL1Branch())) continue;
            if (l2Branch != null && !l2Branch.equals(e.getL2Branch())) continue;
            AllocationOrgEntry allocOrg = allocOrgMap.get(phone);
            String allocDept = (allocOrg != null && allocOrg.getAllocDept() != null && !allocOrg.getAllocDept().isEmpty())
                    ? allocOrg.getAllocDept()
                    : (e.getAllocDept() != null ? e.getAllocDept() : "");
            if (!allocDept.isEmpty()) {
                phoneAllocDept.put(phone, allocDept);
            }
        }

        // Distinct alloc_depts sorted
        List<String> allocDepts = phoneAllocDept.values().stream().distinct().sorted().collect(Collectors.toList());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String allocDept : allocDepts) {
            Set<String> phones = phoneAllocDept.entrySet().stream()
                    .filter(e -> allocDept.equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            Map<String, BigDecimal> fees = aggregateFeesByPhones(allDetails, phones);
            int phoneCount = phones.size();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("alloc_dept", allocDept);
            row.put("l1_branch", l1Branch);
            row.put("l2_branch", l2Branch);
            row.put("platform_fee", fees.get("platform_fee"));
            row.put("monthly_rent_code", fees.get("monthly_rent_code"));
            row.put("domestic_duration", fees.get("domestic_duration"));
            row.put("transfer_duration", fees.get("transfer_duration"));
            row.put("domestic_fee", fees.get("domestic_fee"));
            row.put("international_duration", fees.get("international_duration"));
            row.put("international_fee", fees.get("international_fee"));
            row.put("call_subtotal", fees.get("call_subtotal"));
            row.put("recording_fee", fees.get("recording_fee"));
            row.put("crbt_fee", fees.get("crbt_fee"));
            row.put("flash_fee", fees.get("flash_fee"));
            row.put("total_fee", fees.get("total_fee"));
            row.put("phone_count", phoneCount);
            rows.add(row);
        }
        return rows;
    }

    /**
     * L1 Detail from ownership data: bill_detail rows for all phones in the given batch,
     * enriched with ownership info (l1_branch, l2_branch, alloc_dept, etc.).
     */
    public List<Map<String, Object>> getL1DetailDataByOwnership(Long batchId, String sheetType) {
        String upperSheetType = sheetType.toUpperCase();
        String billingMonth = getBillingMonthFromBatchId(batchId);
        List<PhoneOwnershipEntry> ownershipEntries = loadOwnershipEntries(billingMonth);
        Map<String, PhoneOwnershipEntry> ownershipMap = buildPhoneOwnershipMap(ownershipEntries);
        Map<String, AllocationOrgEntry> allocOrgMap = buildAllocOrgMap(billingMonth);

        List<BillDetail> details = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId).stream()
                .filter(d -> upperSheetType.equals(d.getSheetType()))
                .collect(Collectors.toList());

        return buildOwnershipDetailRows(details, upperSheetType, ownershipMap, allocOrgMap, billingMonth);
    }

    /**
     * L2 Detail from ownership data: bill_detail rows filtered by l1_branch.
     */
    public List<Map<String, Object>> getL2DetailDataByOwnership(Long batchId, String l1Branch, String sheetType) {
        String upperSheetType = sheetType.toUpperCase();
        String billingMonth = getBillingMonthFromBatchId(batchId);
        List<PhoneOwnershipEntry> ownershipEntries = loadOwnershipEntries(billingMonth);

        // 先按 l1_branch 过滤归属，保证明细行的归属列只展示本分行数据（避免跨分行号码串行）
        List<PhoneOwnershipEntry> branchEntries = ownershipEntries.stream()
                .filter(e -> l1Branch.equals(e.getL1Branch()))
                .collect(Collectors.toList());
        Map<String, PhoneOwnershipEntry> ownershipMap = buildPhoneOwnershipMap(branchEntries);
        Map<String, AllocationOrgEntry> allocOrgMap = buildAllocOrgMap(billingMonth);

        Set<String> phones = branchEntries.stream()
                .map(PhoneOwnershipEntry::getPhoneNumber)
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.toSet());

        List<BillDetail> details = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId).stream()
                .filter(d -> upperSheetType.equals(d.getSheetType()))
                .filter(d -> d.getPhoneNumber() != null && phones.contains(d.getPhoneNumber()))
                .collect(Collectors.toList());

        return buildOwnershipDetailRows(details, upperSheetType, ownershipMap, allocOrgMap, billingMonth);
    }

    /**
     * L3 Detail from ownership data: bill_detail rows filtered by l1_branch + l2_branch.
     */
    public List<Map<String, Object>> getL3DetailDataByOwnership(Long batchId, String l1Branch, String l2Branch, String sheetType) {
        String upperSheetType = sheetType.toUpperCase();
        String billingMonth = getBillingMonthFromBatchId(batchId);
        List<PhoneOwnershipEntry> ownershipEntries = loadOwnershipEntries(billingMonth);

        // 先按 l1_branch + l2_branch 过滤归属，保证明细行的归属列只展示本二级分行数据
        List<PhoneOwnershipEntry> branchEntries = ownershipEntries.stream()
                .filter(e -> l1Branch.equals(e.getL1Branch()) && l2Branch.equals(e.getL2Branch()))
                .collect(Collectors.toList());
        Map<String, PhoneOwnershipEntry> ownershipMap = buildPhoneOwnershipMap(branchEntries);
        Map<String, AllocationOrgEntry> allocOrgMap = buildAllocOrgMap(billingMonth);

        Set<String> phones = branchEntries.stream()
                .map(PhoneOwnershipEntry::getPhoneNumber)
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.toSet());

        List<BillDetail> details = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId).stream()
                .filter(d -> upperSheetType.equals(d.getSheetType()))
                .filter(d -> d.getPhoneNumber() != null && phones.contains(d.getPhoneNumber()))
                .collect(Collectors.toList());

        return buildOwnershipDetailRows(details, upperSheetType, ownershipMap, allocOrgMap, billingMonth);
    }

    /**
     * Build detail rows enriched with ownership info (l1_branch, l2_branch, alloc_dept, etc.)
     * instead of sys_organization info.
     * full_path (分摊部门) is sourced from allocation_org_entry by phone number,
     * falling back to phone_ownership_entry.full_path.
     */
    private List<Map<String, Object>> buildOwnershipDetailRows(List<BillDetail> details, String sheetType,
                                                                 Map<String, PhoneOwnershipEntry> ownershipMap,
                                                                 Map<String, AllocationOrgEntry> allocOrgMap,
                                                                 String billingMonth) {
        Map<String, String> recordingDeptMap = "RECORDING".equals(sheetType) ? buildRecordingDeptMap(billingMonth) : null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BillDetail d : details) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("phone_number", d.getPhoneNumber());

            PhoneOwnershipEntry ownership = d.getPhoneNumber() != null ? ownershipMap.get(d.getPhoneNumber()) : null;
            AllocationOrgEntry allocOrg = d.getPhoneNumber() != null ? allocOrgMap.get(d.getPhoneNumber()) : null;
            if (ownership != null) {
                row.put("l1_branch", ownership.getL1Branch() != null ? ownership.getL1Branch() : "");
                row.put("l2_branch", ownership.getL2Branch() != null ? ownership.getL2Branch() : "");

                // alloc_dept: prefer allocation_org_entry, fallback to phone_ownership_entry
                String allocDept = (allocOrg != null && allocOrg.getAllocDept() != null && !allocOrg.getAllocDept().isEmpty())
                        ? allocOrg.getAllocDept()
                        : (ownership.getAllocDept() != null ? ownership.getAllocDept() : "");
                row.put("alloc_dept", allocDept);

                // full_path (分摊部门列): prefer allocation_org_entry.alloc_dept, fallback to ownership.full_path
                String fullPath = (allocOrg != null && allocOrg.getAllocDept() != null && !allocOrg.getAllocDept().isEmpty())
                        ? allocOrg.getAllocDept()
                        : (ownership.getFullPath() != null ? ownership.getFullPath() : "");
                row.put("full_path", fullPath);

                // org_code: prefer allocation_org_entry, fallback to phone_ownership_entry
                String orgCode = (allocOrg != null && allocOrg.getOrgCode() != null && !allocOrg.getOrgCode().isEmpty())
                        ? allocOrg.getOrgCode()
                        : (ownership.getOrgCode() != null ? ownership.getOrgCode() : "");
                row.put("org_code", orgCode);

                // cost_center: prefer allocation_org_entry, fallback to phone_ownership_entry
                String costCenter = (allocOrg != null && allocOrg.getCostCenter() != null && !allocOrg.getCostCenter().isEmpty())
                        ? allocOrg.getCostCenter()
                        : (ownership.getCostCenter() != null ? ownership.getCostCenter() : "");
                row.put("cost_center", costCenter);
            } else {
                // No ownership entry — still try alloc_org_entry for full_path/alloc_dept/org_code/cost_center
                if (allocOrg != null) {
                    row.put("l1_branch", allocOrg.getL1Branch() != null ? allocOrg.getL1Branch() : "");
                    row.put("l2_branch", "");
                    row.put("alloc_dept", allocOrg.getAllocDept() != null ? allocOrg.getAllocDept() : "");
                    row.put("full_path", allocOrg.getAllocDept() != null ? allocOrg.getAllocDept() : "");
                    row.put("org_code", allocOrg.getOrgCode() != null ? allocOrg.getOrgCode() : "");
                    row.put("cost_center", allocOrg.getCostCenter() != null ? allocOrg.getCostCenter() : "");
                } else {
                    row.put("l1_branch", "");
                    row.put("l2_branch", "");
                    row.put("alloc_dept", "");
                    row.put("full_path", "");
                    row.put("org_code", "");
                    row.put("cost_center", "");
                }
            }
            row.put("ownership_source", d.getOwnershipSource() != null ? d.getOwnershipSource() : "");

            String raw = d.getRawData();
            Map<String, Object> parsed = parseRawData(raw);

            switch (sheetType) {
                case "CALL" -> {
                    row.put("extension", d.getExtension() != null ? d.getExtension() : "");
                    row.put("platform_fee", getRawDecimalOrZero(parsed, "platformFee"));
                    row.put("monthly_rent_code", getRawDecimalOrZero(parsed, "monthlyRentCode"));
                    row.put("domestic_duration", getRawDecimalOrZero(parsed, "domesticDuration"));
                    row.put("transfer_duration", getRawDecimalOrZero(parsed, "transferDuration"));
                    row.put("domestic_fee", getRawDecimalOrZero(parsed, "domesticFee"));
                    row.put("international_duration", getRawDecimalOrZero(parsed, "internationalDuration"));
                    row.put("international_fee", getRawDecimalOrZero(parsed, "internationalFee"));
                    row.put("total_fee", getRawDecimalOrZero(parsed, "totalFee"));
                }
                case "RECORDING" -> {
                    row.put("extension", d.getExtension() != null ? d.getExtension() : "");
                    // Prefer dept_name from recording_data_entry (by phone number), fallback to raw recordingDir
                    String recDir = (recordingDeptMap != null && d.getPhoneNumber() != null)
                            ? recordingDeptMap.getOrDefault(d.getPhoneNumber(), getRawString(parsed, "recordingDir"))
                            : getRawString(parsed, "recordingDir");
                    row.put("recording_dir", recDir);
                    row.put("recording_fee", getRawDecimalOrZero(parsed, "recordingFee"));
                }
                case "CRBT" -> {
                    row.put("extension", d.getExtension() != null ? d.getExtension() : "");
                    row.put("crbt_fee", getRawDecimalOrZero(parsed, "crbtFee"));
                }
                case "FLASH_MSG" -> {
                    row.put("extension", d.getExtension() != null ? d.getExtension() : "");
                    row.put("flash_month", d.getFlashMonth() != null ? d.getFlashMonth() : "");
                    row.put("flash_count", getRawDecimalOrZero(parsed, "flashCount"));
                    row.put("flash_msg_fee", getRawDecimalOrZero(parsed, "flashMsgFee"));
                }
                default -> { /* no extra fields */ }
            }
            rows.add(row);
        }
        return rows;
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
    private Map<String, Object> parseRawData(String rawData) {
        if (rawData == null || rawData.isEmpty() || rawData.equals("{}")) return Collections.emptyMap();
        try {
            return MAPPER.readValue(rawData, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) { return Collections.emptyMap(); }
    }

    private BigDecimal getRawDecimal(Map<String, Object> parsed, String field) {
        Object val = parsed.get(field);
        if (val == null) return null;
        if (val instanceof Number) return BigDecimal.valueOf(((Number) val).doubleValue());
        if (val instanceof String) { String s = ((String) val).trim(); return s.isEmpty() ? null : new BigDecimal(s); }
        return null;
    }

    private BigDecimal getRawDecimalOrZero(Map<String, Object> parsed, String field) {
        BigDecimal val = getRawDecimal(parsed, field);
        return val != null ? val : ZERO;
    }

    private String getRawString(Map<String, Object> parsed, String field) {
        Object val = parsed.get(field);
        return val != null ? val.toString() : "";
    }

    private Map<Long, List<BillDetail>> groupCallDetailsByOrgId(List<BillDetail> allDetails) {
        return allDetails.stream()
                .filter(d -> "CALL".equals(d.getSheetType()))
                .collect(Collectors.groupingBy(d -> d.getOrgId() != null ? d.getOrgId() : -1L));
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


    private static BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        return (a != null ? a : ZERO).add(b != null ? b : ZERO);
    }
}

package com.phonecost.service;

import com.phonecost.domain.BillBatch;
import com.phonecost.domain.BillDetail;
import com.phonecost.domain.BillTemplate;
import com.phonecost.repository.BillBatchRepository;
import com.phonecost.repository.BillDetailRepository;
import com.phonecost.repository.BillTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电信账单导入服务
 * 解析账单.xlsx：4个Sheet
 * - 按号码费用(9列): 号码,平台使用费,码号月租费,外呼时长,转接外呼时长,国内费用,国际时长,国际费用,费用小计
 * - 录音(4列): 分机号,外线号码,录音目录,费用小计
 * - 彩铃(3列): 分机号,号码,费用
 * - 闪信(4列): 号码,月份,下发量,金额
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillImportService {

    private final BillBatchRepository batchRepository;
    private final BillDetailRepository detailRepository;
    private final BillTemplateRepository templateRepository;

    // Pattern to extract billing month from sheet name, e.g. "2026年3月按号码费用"
    private static final Pattern MONTH_PATTERN = Pattern.compile("(\\d{4})年(\\d{1,2})月");

    @Transactional
    public BillBatch importBill(MultipartFile file, Long userId) throws IOException {
        String batchNo = "BIL-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String fileName = file.getOriginalFilename();

        // Get or create default template
        BillTemplate template = templateRepository.findByIsActiveAndDeletedAtIsNull((byte) 1)
                .orElseThrow(() -> new IllegalArgumentException("未找到活跃的账单模板"));

        // Extract billing month from first sheet name
        String billingMonth = "unknown";

        BillBatch batch = BillBatch.builder()
                .batchNo(batchNo)
                .billingMonth(billingMonth)
                .fileName(fileName != null ? fileName : "")
                .templateId(template.getId())
                .status((byte) 0) // DRAFT
                .totalAmount(BigDecimal.ZERO)
                .totalCount(0)
                .importStatus((byte) 0)
                .importedBy(userId)
                .build();
        batch = batchRepository.save(batch);

        try (InputStream is = file.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {

            List<BillDetail> allDetails = new ArrayList<>();
            BigDecimal totalAmount = BigDecimal.ZERO;
            int totalCount = 0;

            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                String sheetName = sheet.getSheetName();

                // Extract billing month from sheet name on first sheet
                if (s == 0 && "unknown".equals(billingMonth)) {
                    Matcher m = MONTH_PATTERN.matcher(sheetName);
                    if (m.find()) {
                        int year = Integer.parseInt(m.group(1));
                        int month = Integer.parseInt(m.group(2));
                        billingMonth = String.format("%d-%02d", year, month);
                    }
                }

                // Determine sheet type by name
                String sheetType;
                if (sheetName.contains("按号码费用") || sheetName.contains("号码费用")) {
                    sheetType = "CALL";
                    parseCallSheet(sheet, batch.getId(), allDetails);
                } else if (sheetName.contains("录音")) {
                    sheetType = "RECORDING";
                    parseRecordingSheet(sheet, batch.getId(), allDetails);
                } else if (sheetName.contains("彩铃")) {
                    sheetType = "CRBT";
                    parseCrbtSheet(sheet, batch.getId(), allDetails);
                } else if (sheetName.contains("闪信")) {
                    sheetType = "FLASH_MSG";
                    parseFlashMsgSheet(sheet, batch.getId(), allDetails);
                } else {
                    log.warn("Unknown sheet type: {}, skipping", sheetName);
                    continue;
                }

                // Batch save periodically
                if (allDetails.size() >= 500) {
                    detailRepository.saveAll(allDetails);
                    allDetails.clear();
                }
            }

            // Save remaining
            if (!allDetails.isEmpty()) {
                detailRepository.saveAll(allDetails);
            }

            // Calculate totals
            List<BillDetail> allSaved = detailRepository.findByBatchIdAndDeletedAtIsNull(batch.getId());
            for (BillDetail d : allSaved) {
                totalAmount = totalAmount.add(d.getTotalFee());
                totalCount++;
            }

            // Update billing month
            if (!billingMonth.isEmpty()) {
                batch.setBillingMonth(billingMonth);
            }
            batch.setTotalAmount(totalAmount);
            batch.setTotalCount(totalCount);
            batch.setImportStatus((byte) 1);
            batch = batchRepository.save(batch);

            log.info("Bill import completed: batch={}, month={}, total={}, amount={}",
                    batchNo, billingMonth, totalCount, totalAmount);

        } catch (Exception e) {
            batch.setImportStatus((byte) 2);
            batch.setErrorMessage(e.getMessage());
            batchRepository.save(batch);
            log.error("Bill import failed: batch={}", batchNo, e);
            throw e;
        }

        return batch;
    }

    /**
     * Parse 按号码费用 sheet: 号码(A), 平台使用费(B), 码号月租费(C), 外呼时长(D), 转接外呼时长(E), 国内费用(F), 国际时长(G), 国际费用(H), 费用小计(I)
     * monthly_rent = B + C (平台使用费 + 码号月租费)
     * call_fee = F + H (国内费用 + 国际费用)
     * total_fee = I (费用小计)
     */
    private void parseCallSheet(Sheet sheet, Long batchId, List<BillDetail> details) {
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            String phoneNumber = getCellStringValue(row, 0);
            if (phoneNumber == null || phoneNumber.isEmpty() || phoneNumber.startsWith("AIGC:")) continue;

            BigDecimal platformFee = getCellBigDecimal(row, 1);
            BigDecimal monthlyRentFee = getCellBigDecimal(row, 2);
            BigDecimal domesticFee = getCellBigDecimal(row, 5);
            BigDecimal internationalFee = getCellBigDecimal(row, 7);
            BigDecimal totalFee = getCellBigDecimal(row, 8);

            BigDecimal monthlyRent = platformFee.add(monthlyRentFee);
            BigDecimal callFee = domesticFee.add(internationalFee);

            BillDetail detail = BillDetail.builder()
                    .batchId(batchId)
                    .phoneNumber(phoneNumber.trim())
                    .extension("")
                    .sheetType("CALL")
                    .monthlyRent(monthlyRent)
                    .callFee(callFee)
                    .recordingFee(BigDecimal.ZERO)
                    .crbtFee(BigDecimal.ZERO)
                    .flashMsgFee(BigDecimal.ZERO)
                    .flashMonth("")
                    .totalFee(totalFee)
                    .build();
            details.add(fillDefaults(detail));
        }
    }

    /**
     * Parse 录音 sheet: 分机号(A), 外线号码(B), 录音目录(C), 费用小计(D)
     */
    private void parseRecordingSheet(Sheet sheet, Long batchId, List<BillDetail> details) {
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            String extension = getCellStringValue(row, 0);
            String phoneNumber = getCellStringValue(row, 1);
            if (phoneNumber == null || phoneNumber.isEmpty() || phoneNumber.startsWith("AIGC:")) continue;

            BigDecimal fee = getCellBigDecimal(row, 3);

            BillDetail detail = BillDetail.builder()
                    .batchId(batchId)
                    .phoneNumber(phoneNumber.trim())
                    .extension(extension != null ? extension.trim() : "")
                    .sheetType("RECORDING")
                    .monthlyRent(BigDecimal.ZERO)
                    .callFee(BigDecimal.ZERO)
                    .recordingFee(fee)
                    .crbtFee(BigDecimal.ZERO)
                    .flashMsgFee(BigDecimal.ZERO)
                    .flashMonth("")
                    .totalFee(fee)
                    .build();
            details.add(fillDefaults(detail));
        }
    }

    /**
     * Parse 彩铃 sheet: 分机号(A), 号码(B), 费用(C)
     */
    private void parseCrbtSheet(Sheet sheet, Long batchId, List<BillDetail> details) {
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            String extension = getCellStringValue(row, 0);
            String phoneNumber = getCellStringValue(row, 1);
            if (phoneNumber == null || phoneNumber.isEmpty() || phoneNumber.startsWith("AIGC:")) continue;

            BigDecimal fee = getCellBigDecimal(row, 2);

            BillDetail detail = BillDetail.builder()
                    .batchId(batchId)
                    .phoneNumber(phoneNumber.trim())
                    .extension(extension != null ? extension.trim() : "")
                    .sheetType("CRBT")
                    .monthlyRent(BigDecimal.ZERO)
                    .callFee(BigDecimal.ZERO)
                    .recordingFee(BigDecimal.ZERO)
                    .crbtFee(fee)
                    .flashMsgFee(BigDecimal.ZERO)
                    .flashMonth("")
                    .totalFee(fee)
                    .build();
            details.add(fillDefaults(detail));
        }
    }

    /**
     * Parse 闪信 sheet: 号码(A), 月份(B), 下发量(C), 金额(D)
     * Flash messages are quarterly-settled but count toward current month total
     */
    private void parseFlashMsgSheet(Sheet sheet, Long batchId, List<BillDetail> details) {
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            String phoneNumber = getCellStringValue(row, 0);
            if (phoneNumber == null || phoneNumber.isEmpty() || phoneNumber.startsWith("AIGC:")) continue;

            String flashMonth = getCellStringValue(row, 1);
            BigDecimal fee = getCellBigDecimal(row, 3);

            // Format flash month: 202601 -> 2026-01
            String formattedMonth = flashMonth;
            if (flashMonth != null && flashMonth.matches("\\d{6}")) {
                formattedMonth = flashMonth.substring(0, 4) + "-" + flashMonth.substring(4, 6);
            }

            BillDetail detail = BillDetail.builder()
                    .batchId(batchId)
                    .phoneNumber(phoneNumber.trim())
                    .extension("")
                    .sheetType("FLASH_MSG")
                    .monthlyRent(BigDecimal.ZERO)
                    .callFee(BigDecimal.ZERO)
                    .recordingFee(BigDecimal.ZERO)
                    .crbtFee(BigDecimal.ZERO)
                    .flashMsgFee(fee)
                    .totalFee(fee)
                    .flashMonth(formattedMonth != null ? formattedMonth : "")
                    .build();
            details.add(fillDefaults(detail));
        }
    }

    private BillDetail fillDefaults(BillDetail detail) {
        if (detail.getExtension() == null) detail.setExtension("");
        if (detail.getFlashMonth() == null) detail.setFlashMonth("");
        if (detail.getOwnershipSource() == null) detail.setOwnershipSource("");
        if (detail.getIsException() == null) detail.setIsException((byte) 0);
        if (detail.getIsSeconded() == null) detail.setIsSeconded((byte) 0);
        if (detail.getMonthlyRent() == null) detail.setMonthlyRent(BigDecimal.ZERO);
        if (detail.getCallFee() == null) detail.setCallFee(BigDecimal.ZERO);
        if (detail.getRecordingFee() == null) detail.setRecordingFee(BigDecimal.ZERO);
        if (detail.getCrbtFee() == null) detail.setCrbtFee(BigDecimal.ZERO);
        if (detail.getFlashMsgFee() == null) detail.setFlashMsgFee(BigDecimal.ZERO);
        if (detail.getTotalFee() == null) detail.setTotalFee(BigDecimal.ZERO);
        return detail;
    }

    private String getCellStringValue(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                } else {
                    yield String.valueOf(val);
                }
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue(); }
                catch (Exception ex) { yield String.valueOf(cell.getNumericCellValue()); }
            }
            default -> null;
        };
    }

    private BigDecimal getCellBigDecimal(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) return BigDecimal.ZERO;
        return switch (cell.getCellType()) {
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING -> {
                try { yield new BigDecimal(cell.getStringCellValue().trim()); }
                catch (NumberFormatException e) { yield BigDecimal.ZERO; }
            }
            case FORMULA -> {
                try { yield BigDecimal.valueOf(cell.getNumericCellValue()); }
                catch (Exception e) { yield BigDecimal.ZERO; }
            }
            default -> BigDecimal.ZERO;
        };
    }
}

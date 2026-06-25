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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电信账单导入服务（模板驱动）
 * 根据活跃的 bill_template.sheet_configs JSON 动态解析 Excel
 *
 * 模板 JSON 结构:
 * {
 *   "monthPattern": "(\\d{4})年(\\d{1,2})月",
 *   "sheets": [
 *     {
 *       "sheetNamePattern": "按号码费用",
 *       "sheetType": "CALL",
 *       "phoneColumn": 0,
 *       "extensionColumn": null,
 *       "skipRows": 1,
 *       "isQuarterly": false,
 *       "columns": [{"index": 0, "field": "phoneNumber", "type": "STRING"}, ...],
 *       "computedFields": {"monthlyRent": ["platformFee", "monthlyRentCode"], ...}
 *     }
 *   ]
 * }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillImportService {

    private final BillBatchRepository batchRepository;
    private final BillDetailRepository detailRepository;
    private final BillTemplateRepository templateRepository;
    private final DirectoryEntryRepository directoryEntryRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    // Fallback default pattern when template has none
    private static final Pattern FALLBACK_MONTH_PATTERN = Pattern.compile("(\\d{4})年(\\d{1,2})月");

    @Transactional
    public BillBatch importBill(MultipartFile file, Long userId) throws IOException {
        String batchNo = "BIL-" + LocalDateTime.now().format(DTF);
        String fileName = file.getOriginalFilename();

        // Load active template
        BillTemplate template = templateRepository.findByIsActiveAndDeletedAtIsNull((byte) 1)
                .orElseThrow(() -> new IllegalArgumentException("未找到活跃的账单模板"));

        // Parse template config
        TemplateConfig config = parseTemplateConfig(template);

        BillBatch batch = BillBatch.builder()
                .batchNo(batchNo)
                .billingMonth("unknown")
                .fileName(fileName != null ? fileName : "")
                .templateId(template.getId())
                .status((byte) 0)
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

                // Extract billing month from first sheet
                if (s == 0 && "unknown".equals(batch.getBillingMonth())) {
                    String month = extractMonth(sheetName, config.monthPattern);
                    if (!month.isEmpty()) {
                        batch.setBillingMonth(month);
                    }
                }

                // Match sheet against template configs
                SheetConfig matchedConfig = matchSheetConfig(sheetName, config.sheets);
                if (matchedConfig == null) {
                    log.warn("No matching template config for sheet: {}, skipping", sheetName);
                    continue;
                }

                log.debug("Parsing sheet '{}' with config type={}", sheetName, matchedConfig.sheetType);
                parseSheetWithConfig(sheet, batch.getId(), allDetails, matchedConfig);

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

            // Backfill extensions from directory for sheets without extension column (CALL, FLASH_MSG)
            backfillExtensionsFromDirectory(batch.getId());

            // Calculate totals
            List<BillDetail> allSaved = detailRepository.findByBatchIdAndDeletedAtIsNull(batch.getId());
            for (BillDetail d : allSaved) {
                totalAmount = totalAmount.add(d.getTotalFee() != null ? d.getTotalFee() : BigDecimal.ZERO);
                totalCount++;
            }

            batch.setTotalAmount(totalAmount);
            batch.setTotalCount(totalCount);
            batch.setImportStatus((byte) 1);
            batch = batchRepository.save(batch);

            log.info("Bill import completed: batch={}, month={}, total={}, amount={}, template={}",
                    batchNo, batch.getBillingMonth(), totalCount, totalAmount, template.getName());

        } catch (Exception e) {
            batch.setImportStatus((byte) 2);
            batch.setErrorMessage(e.getMessage());
            batchRepository.save(batch);
            log.error("Bill import failed: batch={}", batchNo, e);
            throw e;
        }

        return batch;
    }

    // ==================== Template Config Parsing ====================

    private TemplateConfig parseTemplateConfig(BillTemplate template) {
        TemplateConfig config = new TemplateConfig();

        // Month pattern
        if (template.getMonthPattern() != null && !template.getMonthPattern().isBlank()) {
            config.monthPattern = Pattern.compile(template.getMonthPattern());
        } else {
            config.monthPattern = FALLBACK_MONTH_PATTERN;
        }

        // Parse sheets array
        try {
            List<Map<String, Object>> sheetsJson = MAPPER.readValue(template.getSheetConfigs(),
                    new TypeReference<List<Map<String, Object>>>() {});

            for (Map<String, Object> sheetMap : sheetsJson) {
                SheetConfig sc = new SheetConfig();
                sc.sheetNamePattern = (String) sheetMap.get("sheetNamePattern");
                sc.sheetType = (String) sheetMap.get("sheetType");
                sc.phoneColumn = toInt(sheetMap.get("phoneColumn"));
                sc.extensionColumn = sheetMap.containsKey("extensionColumn") ? toIntNullable(sheetMap.get("extensionColumn")) : null;
                sc.skipRows = sheetMap.containsKey("skipRows") ? toInt(sheetMap.get("skipRows")) : 1;
                sc.isQuarterly = Boolean.TRUE.equals(sheetMap.get("isQuarterly"));

                // Parse columns
                if (sheetMap.containsKey("columns")) {
                    List<Map<String, Object>> cols = (List<Map<String, Object>>) sheetMap.get("columns");
                    for (Map<String, Object> col : cols) {
                        ColumnConfig cc = new ColumnConfig();
                        cc.index = toInt(col.get("index"));
                        cc.field = (String) col.get("field");
                        cc.type = (String) col.getOrDefault("type", "STRING");
                        sc.columns.add(cc);
                    }
                }

                // Parse legacy feeMappings (convert to columns format)
                if (sc.columns.isEmpty() && sheetMap.containsKey("feeMappings")) {
                    convertLegacyFeeMappings(sc, (Map<String, String>) sheetMap.get("feeMappings"));
                }

                // Parse computed fields
                if (sheetMap.containsKey("computedFields")) {
                    Map<String, Object> cf = (Map<String, Object>) sheetMap.get("computedFields");
                    for (Map.Entry<String, Object> entry : cf.entrySet()) {
                        if (entry.getValue() instanceof List) {
                            sc.computedFields.put(entry.getKey(), (List<String>) entry.getValue());
                        }
                    }
                }

                config.sheets.add(sc);
            }

            log.debug("Parsed template '{}': {} sheet configs", template.getName(), config.sheets.size());

        } catch (Exception e) {
            log.error("Failed to parse template config, using fallback hardcoded logic", e);
            config.sheets.addAll(getFallbackSheetConfigs());
        }

        return config;
    }

    /**
     * Convert legacy letter-based feeMappings (A=col0, B=col1...) to column index format
     */
    private void convertLegacyFeeMappings(SheetConfig sc, Map<String, String> feeMappings) {
        // Add phone column as first STRING column
        sc.columns.add(new ColumnConfig(sc.phoneColumn, "phoneNumber", "STRING"));
        if (sc.extensionColumn != null) {
            sc.columns.add(new ColumnConfig(sc.extensionColumn, "extension", "STRING"));
        }

        // Convert letter mappings to numeric indices
        for (Map.Entry<String, String> entry : feeMappings.entrySet()) {
            int colIndex = letterToIndex(entry.getKey());
            String fieldName = entry.getValue();
            sc.columns.add(new ColumnConfig(colIndex, fieldName, "DECIMAL"));
        }

        // Set up default computed fields based on field names
        if ("CALL".equals(sc.sheetType)) {
            boolean hasPlatform = sc.columns.stream().anyMatch(c -> "platformFee".equals(c.field));
            boolean hasMonthlyRentCode = sc.columns.stream().anyMatch(c -> "monthlyRentCode".equals(c.field));
            boolean hasDomestic = sc.columns.stream().anyMatch(c -> "domesticFee".equals(c.field));
            boolean hasInternational = sc.columns.stream().anyMatch(c -> "internationalFee".equals(c.field));

            if (hasPlatform && hasMonthlyRentCode) {
                sc.computedFields.put("monthlyRent", List.of("platformFee", "monthlyRentCode"));
            }
            if (hasDomestic && hasInternational) {
                sc.computedFields.put("callFee", List.of("domesticFee", "internationalFee"));
            }
        }
    }

    private int letterToIndex(String letter) {
        if (letter == null || letter.isBlank()) return 0;
        char c = letter.toUpperCase().charAt(0);
        if (c >= 'A' && c <= 'Z') return c - 'A';
        try { return Integer.parseInt(letter); } catch (Exception e) { return 0; }
    }

    // ==================== Sheet Matching & Parsing ====================

    private SheetConfig matchSheetConfig(String sheetName, List<SheetConfig> configs) {
        for (SheetConfig sc : configs) {
            if (sc.sheetNamePattern != null && sheetName.matches(".*" + sc.sheetNamePattern + ".*")) {
                return sc;
            }
        }
        return null;
    }

    private void parseSheetWithConfig(Sheet sheet, Long batchId, List<BillDetail> details, SheetConfig config) {
        int skipRows = config.skipRows > 0 ? config.skipRows : 1;

        for (int i = skipRows; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            // Extract values by column config
            Map<String, Object> values = new LinkedHashMap<>();
            for (ColumnConfig col : config.columns) {
                Object val = getCellValue(row, col.index, col.type);
                values.put(col.field, val);
            }

            // Get phone number (required)
            String phoneNumber = getStringValue(values, "phoneNumber");
            if (phoneNumber == null || phoneNumber.isEmpty() || phoneNumber.startsWith("AIGC:")) continue;

            // Store raw column values as JSON for export fidelity
            String rawDataJson;
            try {
                rawDataJson = MAPPER.writeValueAsString(values);
            } catch (Exception e) {
                rawDataJson = "{}";
            }

            // Build BillDetail from extracted values
            BillDetail.BillDetailBuilder builder = BillDetail.builder()
                    .batchId(batchId)
                    .phoneNumber(phoneNumber.trim())
                    .sheetType(config.sheetType)
                    .extension(getStringValue(values, "extension") != null ? getStringValue(values, "extension").trim() : "")
                    .flashMonth("")
                    .rawData(rawDataJson);

            // Map extracted values to BillDetail fields
            builder.monthlyRent(getBigDecimalValue(values, "monthlyRent"));
            builder.callFee(getBigDecimalValue(values, "callFee"));
            builder.recordingFee(getBigDecimalValue(values, "recordingFee"));
            builder.crbtFee(getBigDecimalValue(values, "crbtFee"));
            builder.flashMsgFee(getBigDecimalValue(values, "flashMsgFee"));

            // Apply computed fields
            applyComputedFields(builder, values, config.computedFields);

            // Set totalFee if not already set
            BigDecimal total = builder.build().getTotalFee();
            if (total == null || total.compareTo(BigDecimal.ZERO) == 0) {
                // Sum all fee fields
                BigDecimal sum = BigDecimal.ZERO;
                sum = safeAdd(sum, builder.build().getMonthlyRent());
                sum = safeAdd(sum, builder.build().getCallFee());
                sum = safeAdd(sum, builder.build().getRecordingFee());
                sum = safeAdd(sum, builder.build().getCrbtFee());
                sum = safeAdd(sum, builder.build().getFlashMsgFee());
                builder.totalFee(sum);
            } else {
                builder.totalFee(total);
            }

            // Handle flash month for FLASH_MSG type
            if ("FLASH_MSG".equals(config.sheetType)) {
                String rawMonth = getStringValue(values, "flashMonth");
                if (rawMonth != null && rawMonth.matches("\\d{6}")) {
                    builder.flashMonth(rawMonth.substring(0, 4) + "-" + rawMonth.substring(4, 6));
                } else if (rawMonth != null) {
                    builder.flashMonth(rawMonth);
                }
            }

            details.add(fillDefaults(builder.build()));
        }
    }

    private void applyComputedFields(BillDetail.BillDetailBuilder builder,
                                      Map<String, Object> values,
                                      Map<String, List<String>> computedFields) {
        for (Map.Entry<String, List<String>> entry : computedFields.entrySet()) {
            String targetField = entry.getKey();
            List<String> sourceFields = entry.getValue();

            BigDecimal sum = BigDecimal.ZERO;
            for (String src : sourceFields) {
                BigDecimal val = getBigDecimalValue(values, src);
                sum = sum.add(val != null ? val : BigDecimal.ZERO);
            }

            switch (targetField) {
                case "monthlyRent" -> builder.monthlyRent(sum);
                case "callFee" -> builder.callFee(sum);
                case "recordingFee" -> builder.recordingFee(sum);
                case "crbtFee" -> builder.crbtFee(sum);
                case "flashMsgFee" -> builder.flashMsgFee(sum);
                case "totalFee" -> builder.totalFee(sum);
            }
        }
    }

    // ==================== Cell Value Helpers ====================

    private Object getCellValue(Row row, int colIndex, String type) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;

        if ("DECIMAL".equalsIgnoreCase(type)) {
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
        } else {
            // Default: STRING
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
    }

    private String getStringValue(Map<String, Object> values, String key) {
        Object val = values.get(key);
        return val != null ? val.toString() : null;
    }

    private BigDecimal getBigDecimalValue(Map<String, Object> values, String key) {
        Object val = values.get(key);
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (val instanceof String s) {
            try { return new BigDecimal(s); } catch (Exception e) { return null; }
        }
        return null;
    }

    /**
     * Backfill extension numbers from directory_entry for bill details that have empty extensions.
     * CALL and FLASH_MSG sheets typically don't have extension columns in the source Excel,
     * so we look up the phone number in the latest directory batch and copy the extension.
     */
    private void backfillExtensionsFromDirectory(Long batchId) {
        List<BillDetail> details = detailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<BillDetail> toUpdate = new ArrayList<>();
        int filled = 0;

        for (BillDetail d : details) {
            if (d.getExtension() == null || d.getExtension().isEmpty()) {
                List<DirectoryEntry> entries = directoryEntryRepository.findByPhoneNumberAndDeletedAtIsNull(d.getPhoneNumber());
                if (!entries.isEmpty()) {
                    String ext = entries.get(0).getExtension();
                    if (ext != null && !ext.isEmpty()) {
                        d.setExtension(ext);
                        toUpdate.add(d);
                        filled++;
                    }
                }
            }
        }

        if (!toUpdate.isEmpty()) {
            detailRepository.saveAll(toUpdate);
            log.info("Backfilled {} bill details with extensions from directory for batch {}", filled, batchId);
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

    private String extractMonth(String sheetName, Pattern pattern) {
        Matcher m = pattern.matcher(sheetName);
        if (m.find()) {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            return String.format("%d-%02d", year, month);
        }
        return "";
    }

    private static BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        return (a != null ? a : BigDecimal.ZERO).add(b != null ? b : BigDecimal.ZERO);
    }

    private static int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return 0; }
    }

    private static Integer toIntNullable(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return null; }
    }

    // ==================== Fallback Hardcoded Configs ====================
    // Used when template JSON is missing or unparseable

    private List<SheetConfig> getFallbackSheetConfigs() {
        List<SheetConfig> fallbacks = new ArrayList<>();

        // CALL sheet
        SheetConfig call = new SheetConfig();
        call.sheetNamePattern = "按号码费用|号码费用";
        call.sheetType = "CALL";
        call.phoneColumn = 0;
        call.skipRows = 1;
        call.isQuarterly = false;
        call.columns = Arrays.asList(
                new ColumnConfig(0, "phoneNumber", "STRING"),
                new ColumnConfig(1, "platformFee", "DECIMAL"),
                new ColumnConfig(2, "monthlyRentCode", "DECIMAL"),
                new ColumnConfig(3, "domesticDuration", "DECIMAL"),
                new ColumnConfig(4, "transferDuration", "DECIMAL"),
                new ColumnConfig(5, "domesticFee", "DECIMAL"),
                new ColumnConfig(6, "internationalDuration", "DECIMAL"),
                new ColumnConfig(7, "internationalFee", "DECIMAL"),
                new ColumnConfig(8, "totalFee", "DECIMAL")
        );
        call.computedFields.put("monthlyRent", List.of("platformFee", "monthlyRentCode"));
        call.computedFields.put("callFee", List.of("domesticFee", "internationalFee"));
        fallbacks.add(call);

        // RECORDING sheet
        SheetConfig rec = new SheetConfig();
        rec.sheetNamePattern = "录音";
        rec.sheetType = "RECORDING";
        rec.phoneColumn = 1;
        rec.extensionColumn = 0;
        rec.skipRows = 1;
        rec.isQuarterly = false;
        rec.columns = Arrays.asList(
                new ColumnConfig(0, "extension", "STRING"),
                new ColumnConfig(1, "phoneNumber", "STRING"),
                new ColumnConfig(2, "recordingDir", "STRING"),
                new ColumnConfig(3, "recordingFee", "DECIMAL")
        );
        fallbacks.add(rec);

        // CRBT sheet
        SheetConfig crbt = new SheetConfig();
        crbt.sheetNamePattern = "彩铃";
        crbt.sheetType = "CRBT";
        crbt.phoneColumn = 1;
        crbt.extensionColumn = 0;
        crbt.skipRows = 1;
        crbt.isQuarterly = false;
        crbt.columns = Arrays.asList(
                new ColumnConfig(0, "extension", "STRING"),
                new ColumnConfig(1, "phoneNumber", "STRING"),
                new ColumnConfig(2, "crbtFee", "DECIMAL")
        );
        fallbacks.add(crbt);

        // FLASH_MSG sheet
        SheetConfig flash = new SheetConfig();
        flash.sheetNamePattern = "闪信";
        flash.sheetType = "FLASH_MSG";
        flash.phoneColumn = 0;
        flash.skipRows = 1;
        flash.isQuarterly = true;
        flash.columns = Arrays.asList(
                new ColumnConfig(0, "phoneNumber", "STRING"),
                new ColumnConfig(1, "flashMonth", "STRING"),
                new ColumnConfig(2, "flashCount", "DECIMAL"),
                new ColumnConfig(3, "flashMsgFee", "DECIMAL")
        );
        fallbacks.add(flash);

        return fallbacks;
    }

    // ==================== Inner Config Classes ====================

    private static class TemplateConfig {
        Pattern monthPattern;
        List<SheetConfig> sheets = new ArrayList<>();
    }

    private static class SheetConfig {
        String sheetNamePattern;
        String sheetType;
        int phoneColumn;
        Integer extensionColumn;
        int skipRows;
        boolean isQuarterly;
        List<ColumnConfig> columns = new ArrayList<>();
        Map<String, List<String>> computedFields = new LinkedHashMap<>();
    }

    private static class ColumnConfig {
        int index;
        String field;
        String type;

        ColumnConfig() {}

        ColumnConfig(int index, String field, String type) {
            this.index = index;
            this.field = field;
            this.type = type;
        }
    }
}

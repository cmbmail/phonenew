package com.phonecost.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.enums.CellDataTypeEnum;
import com.alibaba.excel.enums.ReadDefaultReturnEnum;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.metadata.data.ReadCellData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phonecost.domain.*;
import com.phonecost.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电信账单导入服务（性能优化版 v2）
 *
 * 优化点:
 *   1. EasyExcel 流式读取，内存占用低（不再用 XSSFWorkbook 全量加载）
 *   2. 异步导入：API 立即返回批次 ID，后台线程处理，前端轮询进度
 *   3. JdbcTemplate 批量 INSERT（绕过 JPA IDENTITY 策略对 batch 的禁用）
 *   4. Backfill 分机号使用预加载 Map，消除 N+1 查询
 *   5. SQL SUM/COUNT 替代全量重新加载算总额
 *   6. 模板驱动解析逻辑保留不变
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillImportService {

    private final BillBatchRepository batchRepository;
    private final BillDetailRepository detailRepository;
    private final BillTemplateRepository templateRepository;
    private final DirectoryEntryRepository directoryEntryRepository;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 使用 @Lazy 避免循环依赖：
     * BillImportService -> OwnershipMatchService -> BillDetailRepository / BillBatchRepository
     */
    @Lazy
    private final OwnershipMatchService ownershipMatchService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    // Fallback default pattern when template has none
    private static final Pattern FALLBACK_MONTH_PATTERN = Pattern.compile("(\\d{4})年(\\d{1,2})月");

    /** JdbcTemplate 批量插入的批次大小 */
    private static final int BATCH_SIZE = 5000;
    /** 并发导入最大线程数 */
    private static final int MAX_CONCURRENT_IMPORTS = 2;

    /** 有界线程池 */
    private static final ExecutorService IMPORT_EXECUTOR = Executors.newFixedThreadPool(
            MAX_CONCURRENT_IMPORTS,
            r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("bill-import-worker");
                t.setUncaughtExceptionHandler((th, ex) ->
                        log.error("Uncaught exception in bill import thread {}", th.getName(), ex));
                return t;
            }
    );

    /** 进度延迟清理调度器 */
    private static final ScheduledExecutorService CLEANUP_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bill-import-cleanup");
                t.setDaemon(true);
                return t;
            });

    /** 异步任务进度跟踪: batchId -> progress */
    private final Map<Long, ImportProgress> progressMap = new ConcurrentHashMap<>();

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down bill import executors...");
        IMPORT_EXECUTOR.shutdownNow();
        CLEANUP_SCHEDULER.shutdownNow();
        try {
            if (!IMPORT_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Import executor did not terminate within 5 seconds");
            }
            if (!CLEANUP_SCHEDULER.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Cleanup scheduler did not terminate within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 导入电信账单（异步模式）
     * API 立即返回批次信息，后台线程执行实际导入
     */
    public BillBatch importBill(MultipartFile file, Long userId, String billingMonth) throws IOException {
        String batchNo = "BIL-" + LocalDateTime.now().format(DTF);
        String fileName = file.getOriginalFilename();

        // 并发导入数量限制
        long activeImports = progressMap.values().stream()
                .filter(p -> "PENDING".equals(p.getStatus()) || "READING".equals(p.getStatus()) || "WRITING".equals(p.getStatus()))
                .count();
        if (activeImports >= MAX_CONCURRENT_IMPORTS) {
            throw new IllegalStateException("当前有导入任务正在执行，请稍后再试");
        }

        // 1. 在独立短事务中创建批次记录
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // Load active template (need templateId for batch record)
        BillTemplate template = templateRepository.findByIsActiveAndDeletedAtIsNull((byte) 1)
                .orElseThrow(() -> new IllegalArgumentException("未找到活跃的账单模板"));

        BillBatch batch = txTemplate.execute(status -> {
            BillBatch b = BillBatch.builder()
                    .batchNo(batchNo)
                    .billingMonth(billingMonth != null && !billingMonth.isBlank() ? billingMonth : "unknown")
                    .fileName(fileName != null ? fileName : "")
                    .templateId(template.getId())
                    .status((byte) 0)
                    .totalAmount(BigDecimal.ZERO)
                    .totalCount(0)
                    .importStatus((byte) 0)
                    .importedBy(userId)
                    .build();
            return batchRepository.save(b);
        });
        final Long batchId = batch.getId();

        // 2. 保存文件到临时位置
        Path tempFile = Files.createTempFile("bill_import_", ".xlsx");
        try {
            file.transferTo(tempFile.toFile());
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }

        // 3. 初始化进度
        ImportProgress progress = new ImportProgress();
        progressMap.put(batchId, progress);
        progress.setSheetInfo("准备中...");

        // 4. 异步执行导入
        IMPORT_EXECUTOR.submit(() -> {
            TransactionTemplate asyncTx = new TransactionTemplate(transactionManager);
            asyncTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            try {
                doImportAsync(tempFile, batchId, batchNo, template, progress, asyncTx, billingMonth);
            } catch (Exception e) {
                log.error("Async bill import failed: batch={}", batchNo, e);
                progress.setStatus("FAILED");
                progress.setMessage(e.getMessage());
                asyncTx.executeWithoutResult(status -> {
                    batchRepository.findById(batchId).ifPresent(b -> {
                        b.setImportStatus((byte) 2);
                        String msg = e.getMessage();
                        b.setErrorMessage(msg != null ? msg.substring(0, Math.min(msg.length(), 2000)) : "Unknown");
                        batchRepository.save(b);
                    });
                });
            } finally {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
                CLEANUP_SCHEDULER.schedule(() -> progressMap.remove(batchId), 30, TimeUnit.MINUTES);
            }
        });

        return batch;
    }

    /**
     * 查询导入进度
     */
    public ImportProgress getProgress(Long batchId) {
        return progressMap.get(batchId);
    }

    /**
     * 后台导入主流程
     */
    private void doImportAsync(Path tempFile, Long batchId, String batchNo,
                               BillTemplate template, ImportProgress progress,
                               TransactionTemplate txTemplate) {
        doImportAsync(tempFile, batchId, batchNo, template, progress, txTemplate, null);
    }

    private void doImportAsync(Path tempFile, Long batchId, String batchNo,
                               BillTemplate template, ImportProgress progress,
                               TransactionTemplate txTemplate, String presetBillingMonth) {
        long startTime = System.currentTimeMillis();

        // Parse template config
        TemplateConfig config = parseTemplateConfig(template);

        // === 阶段1: 流式读取 Excel (逐 Sheet) ===
        progress.setStatus("READING");

        List<Object[]> allRows = new ArrayList<>(100000);
        int[] totalCountRef = {0};

        // Step 1: Get sheet names using read-only WorkbookFactory (avoids full DOM load)
        // This allows us to match sheets by name before the streaming EasyExcel read
        List<String> sheetNames = new ArrayList<>();
        try (InputStream metaIs = Files.newInputStream(tempFile);
             org.apache.poi.ss.usermodel.Workbook metaWb = org.apache.poi.ss.usermodel.WorkbookFactory.create(metaIs)) {
            for (int i = 0; i < metaWb.getNumberOfSheets(); i++) {
                sheetNames.add(metaWb.getSheetAt(i).getSheetName());
            }
        } catch (Exception e) {
            log.warn("Could not read sheet metadata for bill import", e);
            throw new RuntimeException("无法读取Excel文件结构: " + e.getMessage(), e);
        }
        log.info("Found {} sheets in bill file: {}", sheetNames.size(), sheetNames);

        String billingMonth = (presetBillingMonth != null && !presetBillingMonth.isBlank()) ? presetBillingMonth : "unknown";

        for (int s = 0; s < sheetNames.size(); s++) {
            String sheetName = sheetNames.get(s);

            // Extract billing month from first sheet name (only if not preset)
            if (s == 0 && "unknown".equals(billingMonth)) {
                String month = extractMonth(sheetName, config.monthPattern);
                if (!month.isEmpty()) {
                    billingMonth = month;
                }
            }

            // Match sheet against template configs
            SheetConfig matchedConfig = matchSheetConfig(sheetName, config.sheets);
            if (matchedConfig == null) {
                log.warn("No matching template config for sheet: {}, skipping", sheetName);
                continue;
            }

            progress.setSheetInfo("读取: " + sheetName);
            log.debug("Parsing sheet '{}' with config type={}", sheetName, matchedConfig.sheetType);

            final SheetConfig cfg = matchedConfig;
            final Long bId = batchId;

            // Read this specific sheet with EasyExcel (streaming, low memory)
            int skipRows = cfg.skipRows > 0 ? cfg.skipRows : 1;

            // READ_CELL_DATA mode: receive raw cell data so DECIMAL columns keep the original
            // Excel numeric precision (a "0.00" display format no longer rounds 359.575 to 359.58).
            // Non-DECIMAL columns replicate the legacy STRING-mode conversion behavior.
            EasyExcel.read(tempFile.toFile(), new AnalysisEventListener<Map<Integer, ReadCellData<?>>>() {
                @Override
                public void invoke(Map<Integer, ReadCellData<?>> row, AnalysisContext context) {
                    Integer rowIndex = context.readRowHolder() != null ? context.readRowHolder().getRowIndex() : null;
                    // Extract values by column config
                    Map<String, Object> values = new LinkedHashMap<>();
                    // Build index->field reverse map for this sheet config
                    Map<Integer, String> indexToField = new LinkedHashMap<>();
                    for (ColumnConfig col : cfg.columns) {
                        indexToField.put(col.index, col.field);
                    }
                    for (ColumnConfig col : cfg.columns) {
                        Object val = convertCellData(row.get(col.index), col.type, context, rowIndex, col.index);
                        // Strip Excel empty-date artifacts (e.g. 1904/1/1, 1900/1/0) for recordingDir
                        if ("recordingDir".equals(col.field) && val instanceof String s && isInvalidExcelDate(s)) {
                            val = null;
                        }
                        values.put(col.field, val);
                    }

                    // Get phone number (required)
                    String phoneNumber = getStringValue(values, "phoneNumber");
                    if (phoneNumber == null || phoneNumber.isEmpty() || phoneNumber.startsWith("AIGC:")) return;

                    // Store ALL raw column values as JSON for export & display fidelity.
                    // For columns defined in the template, use the field name;
                    // for columns not in the template, use "col_N" as the key.
                    Map<String, Object> rawAll = new LinkedHashMap<>();
                    for (Map.Entry<Integer, ReadCellData<?>> entry : row.entrySet()) {
                        String fieldName = indexToField.get(entry.getKey());
                        String key = fieldName != null ? fieldName : "col_" + entry.getKey();
                        if (!rawAll.containsKey(key)) {
                            // Prefer the typed value from 'values' if available
                            if (fieldName != null && values.containsKey(fieldName)) {
                                rawAll.put(key, values.get(fieldName));
                            } else {
                                rawAll.put(key, convertCellData(entry.getValue(), null, context, rowIndex, entry.getKey()));
                            }
                        }
                    }
                    // Ensure all template fields are present (even if missing from row)
                    for (ColumnConfig col : cfg.columns) {
                        if (!rawAll.containsKey(col.field)) {
                            rawAll.put(col.field, values.get(col.field));
                        }
                    }

                    String rawDataJson;
                    try {
                        rawDataJson = MAPPER.writeValueAsString(rawAll);
                    } catch (Exception e) {
                        rawDataJson = "{}";
                    }

                    // Map extracted values to BillDetail fields
                    BigDecimal monthlyRent = getBigDecimalValue(values, "monthlyRent");
                    BigDecimal callFee = getBigDecimalValue(values, "callFee");
                    BigDecimal recordingFee = getBigDecimalValue(values, "recordingFee");
                    BigDecimal crbtFee = getBigDecimalValue(values, "crbtFee");
                    BigDecimal flashMsgFee = getBigDecimalValue(values, "flashMsgFee");
                    String extension = getStringValue(values, "extension");
                    String flashMonth = "";

                    // Apply computed fields
                    Map<String, BigDecimal> computedResults = computeFields(values, cfg.computedFields);
                    if (computedResults.containsKey("monthlyRent")) monthlyRent = computedResults.get("monthlyRent");
                    if (computedResults.containsKey("callFee")) callFee = computedResults.get("callFee");
                    if (computedResults.containsKey("recordingFee")) recordingFee = computedResults.get("recordingFee");
                    if (computedResults.containsKey("crbtFee")) crbtFee = computedResults.get("crbtFee");
                    if (computedResults.containsKey("flashMsgFee")) flashMsgFee = computedResults.get("flashMsgFee");

                    // Compute totalFee
                    BigDecimal totalFee = getBigDecimalValue(values, "totalFee");
                    if (computedResults.containsKey("totalFee")) totalFee = computedResults.get("totalFee");
                    if (totalFee == null || totalFee.compareTo(BigDecimal.ZERO) == 0) {
                        totalFee = BigDecimal.ZERO;
                        totalFee = safeAdd(totalFee, monthlyRent);
                        totalFee = safeAdd(totalFee, callFee);
                        totalFee = safeAdd(totalFee, recordingFee);
                        totalFee = safeAdd(totalFee, crbtFee);
                        totalFee = safeAdd(totalFee, flashMsgFee);
                    }

                    // Handle flash month for FLASH_MSG
                    if ("FLASH_MSG".equals(cfg.sheetType)) {
                        String rawMonth = getStringValue(values, "flashMonth");
                        if (rawMonth != null && rawMonth.matches("\\d{6}")) {
                            flashMonth = rawMonth.substring(0, 4) + "-" + rawMonth.substring(4, 6);
                        } else if (rawMonth != null) {
                            flashMonth = rawMonth;
                        }
                    }

                    // Default nulls to zero/empty
                    monthlyRent = monthlyRent != null ? monthlyRent : BigDecimal.ZERO;
                    callFee = callFee != null ? callFee : BigDecimal.ZERO;
                    recordingFee = recordingFee != null ? recordingFee : BigDecimal.ZERO;
                    crbtFee = crbtFee != null ? crbtFee : BigDecimal.ZERO;
                    flashMsgFee = flashMsgFee != null ? flashMsgFee : BigDecimal.ZERO;
                    totalFee = totalFee != null ? totalFee : BigDecimal.ZERO;
                    extension = extension != null ? extension.trim() : "";
                    flashMonth = flashMonth != null ? flashMonth : "";

                    String phoneStr = phoneNumber.trim();

                    allRows.add(new Object[]{
                            bId,
                            phoneStr,
                            extension,
                            cfg.sheetType,
                            monthlyRent,
                            callFee,
                            recordingFee,
                            crbtFee,
                            flashMsgFee,
                            totalFee,
                            "",      // ownership_source
                            (byte)0, // is_exception
                            (byte)0, // is_seconded
                            null,    // org_id
                            flashMonth,
                            rawDataJson
                    });
                    totalCountRef[0]++;
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    log.info("Sheet '{}' parsing complete", sheetName);
                }
            }).readDefaultReturn(ReadDefaultReturnEnum.READ_CELL_DATA).sheet(s).headRowNumber(skipRows).doRead();
        }

        int totalCount = totalCountRef[0];
        progress.setTotal(totalCount);

        log.info("Bill Excel reading done: {} entries from {} sheets in {}ms",
                totalCount, sheetNames.size(), System.currentTimeMillis() - startTime);

        // Update billing month
        final String finalMonth = billingMonth;
        txTemplate.executeWithoutResult(status -> {
            batchRepository.findById(batchId).ifPresent(b -> {
                b.setBillingMonth(finalMonth);
                batchRepository.save(b);
            });
        });

        // === 阶段2: JdbcTemplate 批量 INSERT ===
        progress.setStatus("WRITING");
        progress.setSheetInfo("写入数据库...");
        long writeStart = System.currentTimeMillis();

        String insertSql = "INSERT INTO bill_detail " +
                "(batch_id, phone_number, extension, sheet_type, monthly_rent, call_fee, " +
                "recording_fee, crbt_fee, flash_msg_fee, total_fee, ownership_source, " +
                "is_exception, is_seconded, org_id, flash_month, raw_data, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";

        for (int offset = 0; offset < totalCount; offset += BATCH_SIZE) {
            int end = Math.min(offset + BATCH_SIZE, totalCount);
            List<Object[]> chunk = allRows.subList(offset, end);

            final int chunkEnd = end;
            txTemplate.executeWithoutResult(status -> {
                jdbcTemplate.batchUpdate(insertSql, chunk);
            });

            progress.setProcessed(chunkEnd);
            log.info("Bill import progress: {}/{} ({}%)", chunkEnd, totalCount, chunkEnd * 100 / totalCount);
        }

        // 释放内存
        allRows.clear();

        // === 阶段3: Backfill extensions ===
        progress.setSheetInfo("回填分机号...");
        backfillExtensionsFromDirectoryFast(batchId, txTemplate);

        // === 阶段4: Calculate totals via SQL (avoid full reload) ===
        progress.setSheetInfo("计算汇总...");
        Map<String, Object> totals = txTemplate.execute(status -> {
            Map<String, Object> result = new HashMap<>();
            result.put("total_amount", jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(total_fee), 0) FROM bill_detail WHERE batch_id = ? AND deleted_at IS NULL",
                    BigDecimal.class, batchId));
            result.put("total_count", jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bill_detail WHERE batch_id = ? AND deleted_at IS NULL",
                    Integer.class, batchId));
            return result;
        });

        BigDecimal totalAmount = (BigDecimal) totals.get("total_amount");
        int totalDetailCount = (Integer) totals.get("total_count");

        // Update batch
        txTemplate.executeWithoutResult(status -> {
            batchRepository.findById(batchId).ifPresent(b -> {
                b.setImportStatus((byte) 1);
                b.setTotalCount(totalDetailCount);
                b.setTotalAmount(totalAmount);
                batchRepository.save(b);
            });
        });

        // === 阶段5: 自动触发归属匹配 ===
        try {
            progress.setSheetInfo("执行归属匹配...");
            log.info("Auto-triggering ownership matching for bill batch={}", batchId);
            int matchedCount = ownershipMatchService.matchOwnershipForBillBatch(batchId, null, null, null);
            log.info("Ownership matching completed for bill batch={}, matched={}", batchId, matchedCount);
        } catch (Exception e) {
            log.error("Ownership matching failed for bill batch={}, continuing without matching", batchId, e);
            // 不中断导入流程，匹配失败不影响数据写入
        }

        long elapsed = System.currentTimeMillis() - startTime;
        progress.setProcessed(totalCount);
        progress.setStatus("COMPLETED");
        progress.setElapsedMs(elapsed);

        log.info("Bill import completed: batch={}, month={}, total={}, amount={}, time={}ms (read={}ms, write={}ms)",
                batchNo, ((BigDecimal) totals.get("total_amount")), totalDetailCount,
                totalAmount, elapsed, writeStart - startTime, System.currentTimeMillis() - writeStart);
    }

    // ==================== Backfill Optimized ====================

    /**
     * Backfill extension numbers from directory_entry using pre-loaded Map.
     * Replaces N+1 query pattern with O(1) HashMap lookup + batch UPDATE.
     */
    private void backfillExtensionsFromDirectoryFast(Long batchId, TransactionTemplate txTemplate) {
        // 1. Pre-load directory phone→extension map (projection query — avoids loading full entities)
        Map<String, String> phoneToExtMap = txTemplate.execute(status -> {
            Map<String, String> map = new HashMap<>();
            List<Object[]> rows = directoryEntryRepository.findPhoneAndExtension();
            for (Object[] row : rows) {
                String phone = (String) row[0];
                String ext = (String) row[1];
                if (!map.containsKey(phone)) {
                    map.put(phone, ext);
                }
            }
            return map;
        });

        if (phoneToExtMap.isEmpty()) return;

        // 2. Find bill details with empty extensions for this batch
        List<Map<String, Object>> emptyExtDetails = txTemplate.execute(status ->
                jdbcTemplate.queryForList(
                        "SELECT id, phone_number FROM bill_detail WHERE batch_id = ? AND (extension IS NULL OR extension = '') AND deleted_at IS NULL",
                        batchId)
        );

        if (emptyExtDetails.isEmpty()) return;

        // 3. Batch update extensions
        List<Object[]> updateBatch = new ArrayList<>();
        for (Map<String, Object> row : emptyExtDetails) {
            String phone = (String) row.get("phone_number");
            String ext = phoneToExtMap.get(phone);
            if (ext != null && !ext.isEmpty()) {
                updateBatch.add(new Object[]{ext, row.get("id")});
            }
        }

        if (!updateBatch.isEmpty()) {
            txTemplate.executeWithoutResult(status -> {
                jdbcTemplate.batchUpdate(
                        "UPDATE bill_detail SET extension = ?, updated_at = NOW() WHERE id = ?",
                        updateBatch);
            });
            log.info("Backfilled {} bill details with extensions from directory for batch {}", updateBatch.size(), batchId);
        }
    }

    // ==================== Template Config Parsing (unchanged) ====================

    private TemplateConfig parseTemplateConfig(BillTemplate template) {
        TemplateConfig config = new TemplateConfig();

        if (template.getMonthPattern() != null && !template.getMonthPattern().isBlank()) {
            config.monthPattern = Pattern.compile(template.getMonthPattern());
        } else {
            config.monthPattern = FALLBACK_MONTH_PATTERN;
        }

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

                if (sc.columns.isEmpty() && sheetMap.containsKey("feeMappings")) {
                    convertLegacyFeeMappings(sc, (Map<String, String>) sheetMap.get("feeMappings"));
                }

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

    private void convertLegacyFeeMappings(SheetConfig sc, Map<String, String> feeMappings) {
        sc.columns.add(new ColumnConfig(sc.phoneColumn, "phoneNumber", "STRING"));
        if (sc.extensionColumn != null) {
            sc.columns.add(new ColumnConfig(sc.extensionColumn, "extension", "STRING"));
        }

        for (Map.Entry<String, String> entry : feeMappings.entrySet()) {
            int colIndex = letterToIndex(entry.getKey());
            String fieldName = entry.getValue();
            sc.columns.add(new ColumnConfig(colIndex, fieldName, "DECIMAL"));
        }

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

    // ==================== Sheet Matching (unchanged) ====================

    private SheetConfig matchSheetConfig(String sheetName, List<SheetConfig> configs) {
        for (SheetConfig sc : configs) {
            if (sc.sheetNamePattern != null && sheetName.matches(".*" + sc.sheetNamePattern + ".*")) {
                return sc;
            }
        }
        return null;
    }

    // ==================== Cell Value Helpers (EasyExcel String mode) ====================

    /**
     * Convert a raw EasyExcel cell to typed value.
     * DECIMAL columns use the underlying numberValue (original Excel precision, unaffected by
     * the cell display format such as "0.00" that would otherwise round 359.575 to 359.58);
     * all other columns replicate the legacy STRING-mode conversion via EasyExcel's own
     * converter chain (same formatting as before this change).
     */
    private Object convertCellData(ReadCellData<?> cd, String type, AnalysisContext context,
                                   Integer rowIndex, Integer colIndex) {
        if (cd == null || cd.getType() == CellDataTypeEnum.EMPTY) return null;
        if (cd.getType() == CellDataTypeEnum.NUMBER && "DECIMAL".equalsIgnoreCase(type)) {
            BigDecimal v = cd.getNumberValue();
            if (v == null) return null;
            BigDecimal stripped = v.stripTrailingZeros();
            return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
        }
        var converterMap = context.readSheetHolder() != null ? context.readSheetHolder().converterMap() : null;
        if (converterMap == null) {
            // Defensive fallback (should not happen during normal row processing)
            if (cd.getType() == CellDataTypeEnum.STRING) return cd.getStringValue();
            return cd.getNumberValue() != null ? cd.getNumberValue().toPlainString() : String.valueOf(cd.getData());
        }
        Object converted = com.alibaba.excel.util.ConverterUtils.convertToJavaObject(
                cd, null, null, converterMap, context, rowIndex, colIndex);
        if (converted instanceof String s) {
            // Template-declared STRING columns were trimmed by the old convertCellValue;
            // non-template (col_N) raw columns were not trimmed — keep both behaviors.
            return type != null ? s.trim() : s;
        }
        return converted;
    }

    /**
     * Detect Excel empty-date artifacts rendered as a string (e.g. "1904/1/1",
     * "1900/1/0", "1904/1/0"). These appear when an Excel cell with an empty
     * date (underlying numeric 0) is read back, and should be treated as blank.
     */
    private boolean isInvalidExcelDate(String s) {
        if (s == null || s.isEmpty()) return false;
        String t = s.trim();
        if (!t.contains("/")) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{4})/(\\d{1,2})/(\\d{1,2})$").matcher(t);
        if (!m.matches()) return false;
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        // Empty date numeric 0 -> 1904/1/1 (1904 system) or 1900/1/0 / 1904/1/0 (0 day)
        return (year == 1900 || year == 1904) && month <= 2 && day <= 1;
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
     * Compute fields from template config, returning computed results map.
     * Replaces the old applyComputedFields that needed a Builder.
     */
    private Map<String, BigDecimal> computeFields(Map<String, Object> values,
                                                   Map<String, List<String>> computedFields) {
        Map<String, BigDecimal> results = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : computedFields.entrySet()) {
            String targetField = entry.getKey();
            List<String> sourceFields = entry.getValue();

            BigDecimal sum = BigDecimal.ZERO;
            for (String src : sourceFields) {
                BigDecimal val = getBigDecimalValue(values, src);
                sum = sum.add(val != null ? val : BigDecimal.ZERO);
            }
            results.put(targetField, sum);
        }
        return results;
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

    // ==================== Fallback Hardcoded Configs (unchanged) ====================

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

    // ==================== Import Progress ====================

    public static class ImportProgress {
        private volatile int total;
        private volatile int processed;
        private volatile String status = "PENDING"; // PENDING, READING, WRITING, COMPLETED, FAILED
        private volatile String message;
        private volatile long elapsedMs;
        private volatile String sheetInfo;

        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public int getProcessed() { return processed; }
        public void setProcessed(int processed) { this.processed = processed; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
        public String getSheetInfo() { return sheetInfo; }
        public void setSheetInfo(String sheetInfo) { this.sheetInfo = sheetInfo; }
    }
}

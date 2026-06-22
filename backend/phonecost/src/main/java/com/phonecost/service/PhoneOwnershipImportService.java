package com.phonecost.service;

import com.phonecost.domain.PhoneOwnershipBatch;
import com.phonecost.domain.PhoneOwnershipEntry;
import com.phonecost.repository.PhoneOwnershipBatchRepository;
import com.phonecost.repository.PhoneOwnershipEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 号码归属表导入服务
 * 解析号码归属.xlsx：外线号码 + 描述(用"/"分隔)
 * [例外]前缀的号码标记为P0最高优先级
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneOwnershipImportService {

    private final PhoneOwnershipBatchRepository batchRepository;
    private final PhoneOwnershipEntryRepository entryRepository;

    private static final String EXCEPTION_PREFIX = "[例外]";

    @Transactional
    public PhoneOwnershipBatch importOwnership(MultipartFile file, Long userId) throws IOException {
        String batchNo = "OWN-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String fileName = file.getOriginalFilename();

        // Create batch record
        PhoneOwnershipBatch batch = PhoneOwnershipBatch.builder()
                .batchNo(batchNo)
                .fileName(fileName != null ? fileName : "")
                .totalCount(0)
                .exceptionCount(0)
                .importStatus((byte) 0) // processing
                .importedBy(userId)
                .build();
        batch = batchRepository.save(batch);

        try (InputStream is = file.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {

            Sheet sheet = wb.getSheetAt(0);
            List<PhoneOwnershipEntry> entries = new ArrayList<>();
            int exceptionCount = 0;
            int dataRowCount = 0;

            // Skip header row (row 0)
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String phoneNumber = getCellStringValue(row, 0);
                String description = getCellStringValue(row, 1);

                // Skip AIGC watermark rows
                if (phoneNumber == null || phoneNumber.isEmpty() || phoneNumber.startsWith("AIGC:")) continue;

                // Check for exception marker
                byte isException = (byte) 0;
                String matchLevel = "P2"; // Default for ownership table entries
                if (description != null && description.startsWith(EXCEPTION_PREFIX)) {
                    isException = (byte) 1;
                    matchLevel = "P0"; // Highest priority
                    exceptionCount++;
                }

                PhoneOwnershipEntry entry = PhoneOwnershipEntry.builder()
                        .batchId(batch.getId())
                        .phoneNumber(phoneNumber.trim())
                        .description(description != null ? description.trim() : "")
                        .isException(isException)
                        .matchLevel(matchLevel)
                        .build();
                entries.add(entry);
                dataRowCount++;

                // Batch save every 500 rows
                if (entries.size() >= 500) {
                    entryRepository.saveAll(entries);
                    entries.clear();
                }
            }

            // Save remaining entries
            if (!entries.isEmpty()) {
                entryRepository.saveAll(entries);
            }

            // Update batch stats
            batch.setTotalCount(dataRowCount);
            batch.setExceptionCount(exceptionCount);
            batch.setImportStatus((byte) 1); // success
            batch = batchRepository.save(batch);

            log.info("Ownership import completed: batch={}, total={}, exceptions={}",
                    batchNo, dataRowCount, exceptionCount);

        } catch (Exception e) {
            batch.setImportStatus((byte) 2); // fail
            batch.setErrorMessage(e.getMessage());
            batchRepository.save(batch);
            log.error("Ownership import failed: batch={}", batchNo, e);
            throw e;
        }

        return batch;
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
}

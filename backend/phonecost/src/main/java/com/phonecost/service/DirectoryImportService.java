package com.phonecost.service;

import com.phonecost.domain.DirectoryBatch;
import com.phonecost.domain.DirectoryEntry;
import com.phonecost.repository.DirectoryBatchRepository;
import com.phonecost.repository.DirectoryEntryRepository;
import com.phonecost.repository.SysOrganizationRepository;
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
 * 通讯录导入服务
 * 解析通讯录.xlsx：部门全路径(用"-"分隔) + 用户名称 + 分机号码 + 号码
 * 部门路径如：100001-深圳分行-105326-105328
 * 借调检测：如果员工所在部门与其编制部门不一致
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectoryImportService {

    private final DirectoryBatchRepository batchRepository;
    private final DirectoryEntryRepository entryRepository;
    private final SysOrganizationRepository orgRepository;

    // Common secondment keywords in bank directory
    private static final List<String> SECONDED_KEYWORDS = List.of(
            "借调", "挂职", "交流", "轮岗", "代管", "派驻", "协助"
    );

    @Transactional
    public DirectoryBatch importDirectory(MultipartFile file, Long userId) throws IOException {
        String batchNo = "DIR-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String fileName = file.getOriginalFilename();

        DirectoryBatch batch = DirectoryBatch.builder()
                .batchNo(batchNo)
                .fileName(fileName != null ? fileName : "")
                .totalCount(0)
                .secondedCount(0)
                .importStatus((byte) 0)
                .importedBy(userId)
                .build();
        batch = batchRepository.save(batch);

        try (InputStream is = file.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {

            Sheet sheet = wb.getSheetAt(0);
            List<DirectoryEntry> entries = new ArrayList<>();
            int secondedCount = 0;
            int dataRowCount = 0;

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String deptPath = getCellStringValue(row, 0);
                String username = getCellStringValue(row, 1);
                String extension = getCellStringValue(row, 2);
                String phoneNumber = getCellStringValue(row, 3);

                // Skip AIGC watermark rows
                if (deptPath == null || deptPath.isEmpty() || deptPath.startsWith("AIGC:")) continue;

                // Detect secondment
                byte isSeconded = (byte) 0;
                String secondedKeyword = "";
                if (deptPath != null) {
                    for (String kw : SECONDED_KEYWORDS) {
                        if (deptPath.contains(kw)) {
                            isSeconded = (byte) 1;
                            secondedKeyword = kw;
                            secondedCount++;
                            break;
                        }
                    }
                }

                // Try to match org from dept path
                // Path format: code-name-code-name, last segment is the deepest department
                Long orgId = matchOrgFromPath(deptPath);

                DirectoryEntry entry = DirectoryEntry.builder()
                        .batchId(batch.getId())
                        .deptPath(deptPath != null ? deptPath.trim() : "")
                        .username(username != null ? username.trim() : "")
                        .extension(extension != null ? extension.trim() : "")
                        .phoneNumber(phoneNumber != null ? phoneNumber.trim() : "")
                        .orgId(orgId)
                        .isSeconded(isSeconded)
                        .secondedKeyword(secondedKeyword)
                        .build();
                entries.add(entry);
                dataRowCount++;

                if (entries.size() >= 500) {
                    entryRepository.saveAll(entries);
                    entries.clear();
                }
            }

            if (!entries.isEmpty()) {
                entryRepository.saveAll(entries);
            }

            batch.setTotalCount(dataRowCount);
            batch.setSecondedCount(secondedCount);
            batch.setImportStatus((byte) 1);
            batch = batchRepository.save(batch);

            log.info("Directory import completed: batch={}, total={}, seconded={}",
                    batchNo, dataRowCount, secondedCount);

        } catch (Exception e) {
            batch.setImportStatus((byte) 2);
            batch.setErrorMessage(e.getMessage());
            batchRepository.save(batch);
            log.error("Directory import failed: batch={}", batchNo, e);
            throw e;
        }

        return batch;
    }

    /**
     * Try to match organization from department path.
     * Path format: "100001-深圳分行-105326-105328"
     * Try matching by org code (numeric segments in path)
     */
    private Long matchOrgFromPath(String deptPath) {
        if (deptPath == null || deptPath.isEmpty()) return null;

        String[] segments = deptPath.split("-");
        // Try from deepest to shallowest for best match
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i].trim();
            // Check if segment looks like an org code (all digits, 4-6 chars)
            if (segment.matches("\\d{4,6}")) {
                var org = orgRepository.findByCodeAndDeletedAtIsNull(segment);
                if (org.isPresent()) {
                    return org.get().getId();
                }
            }
        }
        return null;
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

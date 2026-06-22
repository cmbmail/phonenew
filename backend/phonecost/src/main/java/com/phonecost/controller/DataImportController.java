package com.phonecost.controller;

import com.phonecost.domain.*;
import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.*;
import com.phonecost.service.BillImportService;
import com.phonecost.service.DirectoryImportService;
import com.phonecost.service.OwnershipMatchService;
import com.phonecost.service.PhoneOwnershipImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 数据导入Controller
 * 提供号码归属、通讯录、电信账单的导入API
 */
@RestController
@RequestMapping("/import")
@RequiredArgsConstructor
public class DataImportController {

    private final PhoneOwnershipImportService ownershipImportService;
    private final DirectoryImportService directoryImportService;
    private final BillImportService billImportService;
    private final OwnershipMatchService ownershipMatchService;

    private final PhoneOwnershipBatchRepository ownershipBatchRepository;
    private final PhoneOwnershipEntryRepository ownershipEntryRepository;
    private final DirectoryBatchRepository directoryBatchRepository;
    private final DirectoryEntryRepository directoryEntryRepository;
    private final BillBatchRepository billBatchRepository;
    private final BillDetailRepository billDetailRepository;

    // ==================== 号码归属导入 ====================

    @PostMapping("/ownership")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importOwnership(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") Long userId) {
        try {
            PhoneOwnershipBatch batch = ownershipImportService.importOwnership(file, userId);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "batch_id", batch.getId(),
                    "batch_no", batch.getBatchNo(),
                    "total_count", batch.getTotalCount(),
                    "exception_count", batch.getExceptionCount(),
                    "import_status", batch.getImportStatus()
            )));
        } catch (Exception e) {
            throw new IllegalArgumentException("号码归属导入失败: " + e.getMessage());
        }
    }

    @GetMapping("/ownership/batches")
    public ResponseEntity<ApiResponse<List<PhoneOwnershipBatch>>> listOwnershipBatches() {
        return ResponseEntity.ok(ApiResponse.ok(
                ownershipBatchRepository.findAll()));
    }

    @GetMapping("/ownership/entries/{batchId}")
    public ResponseEntity<ApiResponse<List<PhoneOwnershipEntry>>> listOwnershipEntries(
            @PathVariable Long batchId) {
        return ResponseEntity.ok(ApiResponse.ok(
                ownershipEntryRepository.findByBatchIdAndDeletedAtIsNull(batchId)));
    }

    // ==================== 通讯录导入 ====================

    @PostMapping("/directory")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importDirectory(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") Long userId) {
        try {
            DirectoryBatch batch = directoryImportService.importDirectory(file, userId);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "batch_id", batch.getId(),
                    "batch_no", batch.getBatchNo(),
                    "total_count", batch.getTotalCount(),
                    "seconded_count", batch.getSecondedCount(),
                    "import_status", batch.getImportStatus()
            )));
        } catch (Exception e) {
            throw new IllegalArgumentException("通讯录导入失败: " + e.getMessage());
        }
    }

    @GetMapping("/directory/batches")
    public ResponseEntity<ApiResponse<List<DirectoryBatch>>> listDirectoryBatches() {
        return ResponseEntity.ok(ApiResponse.ok(
                directoryBatchRepository.findAll()));
    }

    @GetMapping("/directory/entries/{batchId}")
    public ResponseEntity<ApiResponse<List<DirectoryEntry>>> listDirectoryEntries(
            @PathVariable Long batchId) {
        return ResponseEntity.ok(ApiResponse.ok(
                directoryEntryRepository.findByBatchIdAndDeletedAtIsNull(batchId)));
    }

    // ==================== 电信账单导入 ====================

    @PostMapping("/bill")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importBill(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") Long userId) {
        try {
            BillBatch batch = billImportService.importBill(file, userId);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "batch_id", batch.getId(),
                    "batch_no", batch.getBatchNo(),
                    "billing_month", batch.getBillingMonth(),
                    "total_count", batch.getTotalCount(),
                    "total_amount", batch.getTotalAmount(),
                    "import_status", batch.getImportStatus()
            )));
        } catch (Exception e) {
            throw new IllegalArgumentException("账单导入失败: " + e.getMessage());
        }
    }

    @GetMapping("/bill/batches")
    public ResponseEntity<ApiResponse<List<BillBatch>>> listBillBatches() {
        return ResponseEntity.ok(ApiResponse.ok(
                billBatchRepository.findAll()));
    }

    @GetMapping("/bill/details/{batchId}")
    public ResponseEntity<ApiResponse<List<BillDetail>>> listBillDetails(
            @PathVariable Long batchId) {
        return ResponseEntity.ok(ApiResponse.ok(
                billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId)));
    }

    // ==================== 归属匹配 ====================

    @PostMapping("/match-ownership")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchOwnership(
            @RequestBody Map<String, Long> body) {
        Long billBatchId = body.get("bill_batch_id");
        Long ownershipBatchId = body.get("ownership_batch_id");
        Long directoryBatchId = body.get("directory_batch_id");

        if (billBatchId == null) {
            throw new IllegalArgumentException("bill_batch_id 不能为空");
        }

        int matched = ownershipMatchService.matchOwnershipForBillBatch(
                billBatchId, ownershipBatchId, directoryBatchId);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "bill_batch_id", billBatchId,
                "matched_count", matched
        )));
    }
}

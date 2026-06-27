package com.phonecost.controller;

import com.phonecost.domain.*;
import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.*;
import com.phonecost.service.BillImportService;
import com.phonecost.service.DataScope;
import com.phonecost.service.DataScopeService;
import com.phonecost.service.DirectoryImportService;
import com.phonecost.service.OwnershipMatchService;
import com.phonecost.service.PhoneOwnershipImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final DataScopeService dataScopeService;

    private final PhoneOwnershipBatchRepository ownershipBatchRepository;
    private final PhoneOwnershipEntryRepository ownershipEntryRepository;
    private final DirectoryBatchRepository directoryBatchRepository;
    private final DirectoryEntryRepository directoryEntryRepository;
    private final BillBatchRepository billBatchRepository;
    private final BillDetailRepository billDetailRepository;
    private final DataSnapshotRepository dataSnapshotRepository;

    // ==================== 号码归属导入 ====================

    @PostMapping("/ownership")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
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
    public ResponseEntity<ApiResponse<List<PhoneOwnershipBatch>>> listOwnershipBatches(
            @RequestAttribute("userId") Long userId) {
        // 归属批次是全局的，所有用户可见（不按组织过滤）
        return ResponseEntity.ok(ApiResponse.ok(ownershipBatchRepository.findAll()));
    }

    @GetMapping("/ownership/entries/{batchId}")
    public ResponseEntity<ApiResponse<List<PhoneOwnershipEntry>>> listOwnershipEntries(
            @PathVariable Long batchId,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        List<PhoneOwnershipEntry> all = ownershipEntryRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<PhoneOwnershipEntry> filtered = scope.filterByOrgId(all, PhoneOwnershipEntry::getOrgId);
        return ResponseEntity.ok(ApiResponse.ok(filtered));
    }

    // ==================== 通讯录导入 ====================

    @PostMapping("/directory")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
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
    public ResponseEntity<ApiResponse<List<DirectoryBatch>>> listDirectoryBatches(
            @RequestAttribute("userId") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(directoryBatchRepository.findAll()));
    }

    @GetMapping("/directory/entries/{batchId}")
    public ResponseEntity<ApiResponse<List<DirectoryEntry>>> listDirectoryEntries(
            @PathVariable Long batchId,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        List<DirectoryEntry> all = directoryEntryRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<DirectoryEntry> filtered = scope.filterByOrgId(all, DirectoryEntry::getOrgId);
        return ResponseEntity.ok(ApiResponse.ok(filtered));
    }

    // ==================== 电信账单导入 ====================

    @PostMapping("/bill")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
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
    public ResponseEntity<ApiResponse<List<BillBatch>>> listBillBatches(
            @RequestAttribute("userId") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(billBatchRepository.findAll()));
    }

    @GetMapping("/bill/details/{batchId}")
    public ResponseEntity<ApiResponse<List<BillDetail>>> listBillDetails(
            @PathVariable Long batchId,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        List<BillDetail> all = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<BillDetail> filtered = scope.filterByOrgId(all, BillDetail::getOrgId);
        return ResponseEntity.ok(ApiResponse.ok(filtered));
    }

    // ==================== 归属匹配 ====================

    @PostMapping("/match-ownership")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
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

        // Save or update snapshot record
        Optional<DataSnapshot> existing = dataSnapshotRepository.findByBillBatchIdAndDeletedAtIsNull(billBatchId);
        DataSnapshot snapshot;
        if (existing.isPresent()) {
            snapshot = existing.get();
            snapshot.setOwnershipBatchId(ownershipBatchId);
            snapshot.setDirectoryBatchId(directoryBatchId);
            snapshot.setMatchedCount(matched);
        } else {
            snapshot = DataSnapshot.builder()
                    .billBatchId(billBatchId)
                    .ownershipBatchId(ownershipBatchId)
                    .directoryBatchId(directoryBatchId)
                    .matchedCount(matched)
                    .build();
        }
        dataSnapshotRepository.save(snapshot);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "bill_batch_id", billBatchId,
                "matched_count", matched
        )));
    }

    // ==================== 通讯录快照 ====================

    @PutMapping("/directory/entries/{id}/clear-exception")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<DirectoryEntry>> clearException(@PathVariable Long id) {
        DirectoryEntry entry = directoryEntryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在: " + id));
        entry.setIsSeconded((byte) 0);
        entry.setSecondedKeyword("");
        directoryEntryRepository.save(entry);
        return ResponseEntity.ok(ApiResponse.ok(entry));
    }

    @PutMapping("/directory/entries/{id}/sync-from-match")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<DirectoryEntry>> syncFromMatch(@PathVariable Long id) {
        DirectoryEntry entry = directoryEntryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在: " + id));
        // Find matching non-exception entry by phone number in same batch
        List<DirectoryEntry> matches = directoryEntryRepository.findByBatchIdAndDeletedAtIsNull(entry.getBatchId());
        DirectoryEntry match = matches.stream()
                .filter(e -> e.getPhoneNumber().equals(entry.getPhoneNumber()) && !e.getId().equals(id) && e.getIsSeconded() == 0)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到匹配的当前数据记录"));
        entry.setDeptPath(match.getDeptPath());
        entry.setUsername(match.getUsername());
        entry.setExtension(match.getExtension());
        entry.setIsSeconded((byte) 0);
        entry.setSecondedKeyword("");
        directoryEntryRepository.save(entry);
        return ResponseEntity.ok(ApiResponse.ok(entry));
    }

    @PutMapping("/directory/entries/batch-clear-exception")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchClearException(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids 不能为空");
        }
        int count = 0;
        for (Long id : ids) {
            DirectoryEntry entry = directoryEntryRepository.findById(id).orElse(null);
            if (entry != null) {
                entry.setIsSeconded((byte) 0);
                entry.setSecondedKeyword("");
                directoryEntryRepository.save(entry);
                count++;
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("cleared", count)));
    }

    @PutMapping("/directory/batches/{id}/month")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<DirectoryBatch>> setDirectoryMonth(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        DirectoryBatch batch = directoryBatchRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在: " + id));
        String month = body.get("billing_month");
        if (month == null || !month.matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("月份格式错误，应为 yyyy-MM");
        }
        batch.setBillingMonth(month);
        directoryBatchRepository.save(batch);
        return ResponseEntity.ok(ApiResponse.ok(batch));
    }

    @GetMapping("/directory/snapshots")
    public ResponseEntity<ApiResponse<List<DirectoryBatch>>> listDirectorySnapshots() {
        List<DirectoryBatch> snapshots = directoryBatchRepository.findAll().stream()
                .filter(b -> b.getBillingMonth() != null)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(snapshots));
    }

    // ==================== 数据快照 ====================

    @GetMapping("/snapshots")
    public ResponseEntity<ApiResponse<List<DataSnapshot>>> listSnapshots() {
        List<DataSnapshot> snapshots = dataSnapshotRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();
        return ResponseEntity.ok(ApiResponse.ok(snapshots));
    }

    @GetMapping("/snapshots/{billBatchId}")
    public ResponseEntity<ApiResponse<DataSnapshot>> getSnapshot(@PathVariable Long billBatchId) {
        DataSnapshot snapshot = dataSnapshotRepository.findByBillBatchIdAndDeletedAtIsNull(billBatchId)
                .orElseThrow(() -> new IllegalArgumentException("未找到账单批次 " + billBatchId + " 的快照记录"));
        return ResponseEntity.ok(ApiResponse.ok(snapshot));
    }
}

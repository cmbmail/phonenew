package com.phonecost.controller;

import com.phonecost.domain.AllocationResult;
import com.phonecost.domain.BillBatch;
import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.AllocationResultRepository;
import com.phonecost.repository.BillBatchRepository;
import com.phonecost.service.AllocationConfirmService;
import com.phonecost.service.AllocationExportService;
import com.phonecost.service.AllocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 费用分摊Controller
 * 分摊计算 + 确认/撤回 + 导出
 */
@RestController
@RequestMapping("/allocation")
@RequiredArgsConstructor
public class AllocationController {

    private final AllocationService allocationService;
    private final AllocationConfirmService confirmService;
    private final AllocationExportService exportService;
    private final AllocationResultRepository resultRepository;
    private final BillBatchRepository billBatchRepository;

    // ==================== 分摊计算 ====================

    @PostMapping("/calculate")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> calculate(
            @RequestBody Map<String, Long> body) {
        Long billBatchId = body.get("bill_batch_id");
        if (billBatchId == null) {
            throw new IllegalArgumentException("bill_batch_id 不能为空");
        }
        List<AllocationResult> results = allocationService.calculateAllocation(billBatchId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "bill_batch_id", billBatchId,
                "org_count", results.size()
        )));
    }

    // ==================== 查询结果 ====================

    @GetMapping("/results/{batchId}")
    public ResponseEntity<ApiResponse<List<AllocationResult>>> getResults(
            @PathVariable Long batchId) {
        return ResponseEntity.ok(ApiResponse.ok(
                resultRepository.findByBatchIdAndDeletedAtIsNull(batchId)));
    }

    @GetMapping("/batches")
    public ResponseEntity<ApiResponse<List<BillBatch>>> listBatches() {
        return ResponseEntity.ok(ApiResponse.ok(billBatchRepository.findAll()));
    }

    // ==================== 确认/撤回 ====================

    @PostMapping("/confirm")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirm(
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long userId) {
        Long batchId = toLong(body.get("batch_id"));
        Long orgId = toLong(body.get("org_id"));
        if (batchId == null || orgId == null) {
            throw new IllegalArgumentException("batch_id 和 org_id 不能为空");
        }
        AllocationResult result = confirmService.confirm(batchId, orgId, userId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "org_id", result.getOrgId(),
                "confirm_status", (int) result.getConfirmStatus()
        )));
    }

    @PostMapping("/confirm-all")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmAll(
            @RequestBody Map<String, Long> body,
            @RequestAttribute("userId") Long userId) {
        Long batchId = body.get("batch_id");
        if (batchId == null) {
            throw new IllegalArgumentException("batch_id 不能为空");
        }
        int count = confirmService.confirmAll(batchId, userId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("confirmed_count", count)));
    }

    @PostMapping("/withdraw")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> withdraw(
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long userId) {
        Long batchId = toLong(body.get("batch_id"));
        Long orgId = toLong(body.get("org_id"));
        String reason = (String) body.get("reason");
        if (batchId == null || orgId == null) {
            throw new IllegalArgumentException("batch_id 和 org_id 不能为空");
        }
        List<AllocationResult> results = confirmService.withdraw(batchId, orgId, userId, reason);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "org_id", orgId,
                "result_count", results.size()
        )));
    }

    // ==================== 导出 ====================

    @GetMapping("/export/summary")
    public ResponseEntity<byte[]> exportSummary(
            @RequestParam Long batchId,
            @RequestParam(required = false) Long branchOrgId) throws Exception {
        byte[] data = exportService.exportSummary(batchId, branchOrgId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"分行费用分摊汇总.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    @GetMapping("/export/detail")
    public ResponseEntity<byte[]> exportDetail(
            @RequestParam Long batchId,
            @RequestParam(required = false) Long branchOrgId) throws Exception {
        byte[] data = exportService.exportDetail(batchId, branchOrgId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"分行费用分摊明细.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.valueOf(val.toString());
    }
}

package com.phonecost.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分摊计算请求 DTO
 */
@Data
public class AllocationCalculateRequest {
    @NotNull(message = "bill_batch_id 不能为空")
    private Long billBatchId;
    private Long ownershipBatchId;
    private Long directoryBatchId;
}

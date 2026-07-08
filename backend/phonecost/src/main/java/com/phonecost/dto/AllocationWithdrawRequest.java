package com.phonecost.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分摊撤回请求 DTO
 */
@Data
public class AllocationWithdrawRequest {
    @NotNull(message = "batch_id 不能为空")
    private Long batchId;
    @NotNull(message = "org_id 不能为空")
    private Long orgId;
    private String reason;
}

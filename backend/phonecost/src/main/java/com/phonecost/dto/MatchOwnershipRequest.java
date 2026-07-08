package com.phonecost.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 归属匹配请求 DTO
 */
@Data
public class MatchOwnershipRequest {
    @NotNull(message = "bill_batch_id 不能为空")
    private Long billBatchId;
    private Long ownershipBatchId;
    private Long directoryBatchId;
}

package com.phonecost.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 批量确认请求 DTO
 */
@Data
public class AllocationConfirmAllRequest {
    @NotNull(message = "batch_id 不能为空")
    private Long batchId;
}

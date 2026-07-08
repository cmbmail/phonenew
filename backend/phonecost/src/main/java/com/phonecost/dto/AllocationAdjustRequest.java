package com.phonecost.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 费用调整请求 DTO
 */
@Data
public class AllocationAdjustRequest {
    @NotNull(message = "batch_id 不能为空")
    private Long batchId;
    @NotBlank(message = "phone_number 不能为空")
    private String phoneNumber;
    @NotNull(message = "from_org_id 不能为空")
    private Long fromOrgId;
    @NotNull(message = "to_org_id 不能为空")
    private Long toOrgId;
    private String reason;
}

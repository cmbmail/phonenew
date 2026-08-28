package com.phonecost.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * H-S01: Replace Map<String, String> for AllocationDeptController.addEntry
 */
@Data
public class AddAllocationDeptEntryRequest {
    @NotBlank(message = "月份不能为空")
    private String billingMonth;

    private String phoneNumber;
    private String branch;
    private String deptName;
    private String fullPath;
    private String orgCode;
    private String costCenter;
}

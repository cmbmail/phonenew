package com.phonecost.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * H-S01: Replace Map<String, String> for addRecordingDataEntry
 */
@Data
public class AddRecordingDataEntryRequest {
    @NotBlank(message = "月份不能为空")
    private String billingMonth;

    private String extension;
    private String phoneNumber;
    private String deptName;
    private String remark;
}
